package com.rikkei.microservice.orderservice.service.impl;

import com.rikkei.microservice.orderservice.dto.OrderRequestDTO;
import com.rikkei.microservice.orderservice.dto.OrderResponseDTO;
import com.rikkei.microservice.orderservice.dto.Product;
import com.rikkei.microservice.orderservice.entity.Order;
import com.rikkei.microservice.orderservice.event.OrderCreatedEvent;
import com.rikkei.microservice.orderservice.exception.OutOfStockException;
import com.rikkei.microservice.orderservice.exception.ResourceNotFoundException;
import com.rikkei.microservice.orderservice.kafka.OrderProducer;
import com.rikkei.microservice.orderservice.repository.OrderRepository;
import com.rikkei.microservice.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final DiscoveryClient discoveryClient;
    private final RestClient restClient;
    private final OrderProducer orderProducer;
    private final RedissonClient redissonClient;

    @Override
    public OrderResponseDTO createOrder(OrderRequestDTO requestDTO) {
        if (requestDTO.getQuantity() == null || requestDTO.getQuantity() <= 0) {
            throw new IllegalArgumentException("Số lượng (quantity) phải lớn hơn 0");
        }

        Long productId = requestDTO.getProductId();
        int requestedQuantity = requestDTO.getQuantity();

        // 1. Tạo khóa phân tán Redisson theo key: lock:product:{id}
        String lockKey = "lock:product:" + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Chờ tối đa 5 giây để giành khóa, giữ khóa tối đa 10 giây
            boolean isLocked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                log.warn("===> [Flash Sale] Không giành được khóa {} do lưu lượng truy cập cao", lockKey);
                throw new OutOfStockException("Hệ thống đang quá tải trong Flash Sale, vui lòng thử lại!");
            }

            log.info("===> [Flash Sale] Thread {} đã giành được khóa {} thành công",
                    Thread.currentThread().getName(), lockKey);

            // 2. Gọi sang Product-Service để kiểm tra tồn kho và trừ số lượng trong Flash Sale
            Product product;
            try {
                // Gọi API purchase của Product Service có phân tán
                product = callProductServicePurchase(productId, requestedQuantity);
            } catch (OutOfStockException ex) {
                throw ex;
            } catch (Exception ex) {
                // Nếu không gọi được API purchase, fallback sang getProduct để kiểm tra tồn kho
                log.warn("Gọi API purchase từ Product Service gặp lỗi ({}), fallback kiểm tra qua getProduct", ex.getMessage());
                product = getProductFromProductService2(String.valueOf(productId));
                if (product != null && product.getStockQuantity() != null && product.getStockQuantity() < requestedQuantity) {
                    throw new OutOfStockException("Sản phẩm đã hết hàng");
                }
            }

            BigDecimal unitPrice = (product != null && product.getPrice() != null)
                    ? product.getPrice()
                    : BigDecimal.valueOf(150.00);
            BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(requestedQuantity));

            // 3. Tạo và lưu đơn hàng vào DB
            Order order = Order.builder()
                    .customerId(requestDTO.getCustomerId())
                    .productId(productId)
                    .orderDate(LocalDateTime.now())
                    .totalAmount(totalAmount)
                    .build();

            Order savedOrder = orderRepository.save(order);
            log.info("===> [Order Service] Đã lưu đơn hàng vào DB thành công với Order ID: {}", savedOrder.getId());

            // 4. Bắn sự kiện OrderCreatedEvent vào Kafka topic 'order-events'
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId(savedOrder.getId())
                    .customerId(savedOrder.getCustomerId())
                    .productId(savedOrder.getProductId())
                    .quantity(requestedQuantity)
                    .totalAmount(savedOrder.getTotalAmount())
                    .timestamp(savedOrder.getOrderDate())
                    .build();

            orderProducer.sendOrderCreatedEvent(event);

            return OrderResponseDTO.fromEntity(savedOrder);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tiến trình bị gián đoạn trong khi chờ khóa", e);
        } finally {
            // 5. BẮT BUỘC: Luôn giải phóng khóa trong khối finally
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("===> [Flash Sale] Đã giải phóng khóa {} trong Order Service", lockKey);
            }
        }
    }

    private Product callProductServicePurchase(Long productId, Integer quantity) {
        String targetUrl = "http://ProductService/api/v1/products/" + productId + "/purchase?quantity=" + quantity;
        try {
            return restClient.post()
                    .uri(targetUrl)
                    .retrieve()
                    .body(Product.class);
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest ex) {
            throw new OutOfStockException("Sản phẩm đã hết hàng");
        } catch (Exception ex) {
            throw new RuntimeException("Lỗi khi kết nối với Product Service: " + ex.getMessage(), ex);
        }
    }

    @Override
    public OrderResponseDTO getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with ID: " + id));
        return OrderResponseDTO.fromEntity(order);
    }

    @Override
    public List<OrderResponseDTO> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(OrderResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public Product getProductFromProductService(String productId) {
        List<ServiceInstance> instances = discoveryClient.getInstances("product-service");
        if (instances == null || instances.isEmpty()) {
            instances = discoveryClient.getInstances("PRODUCT-SERVICE");
        }

        if (instances == null || instances.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PRODUCT-SERVICE hiện không khả dụng (Không tìm thấy instance nào)"
            );
        }

        ServiceInstance productInstance = instances.get(0);
        String baseUrl = productInstance.getUri().toString();
        String targetUrl = baseUrl + "/api/v1/products/" + productId;

        try {
            return restClient.get()
                    .uri(targetUrl)
                    .retrieve()
                    .body(Product.class);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể kết nối tới PRODUCT-SERVICE: " + ex.getMessage(),
                    ex
            );
        }
    }

    @Override
    public Product getProductFromProductService(Long productId) {
        return getProductFromProductService(String.valueOf(productId));
    }

    @Override
    public Product getProductFromProductService2(String productId) {
        String targetUrl = "http://ProductService/api/v1/products/" + productId;

        try {
            return restClient.get()
                    .uri(targetUrl)
                    .retrieve()
                    .body(Product.class);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể kết nối tới PRODUCT-SERVICE: " + ex.getMessage(),
                    ex
            );
        }
    }

    @Override
    public Product getProductFromProductService2(Long productId) {
        return getProductFromProductService2(String.valueOf(productId));
    }
}
