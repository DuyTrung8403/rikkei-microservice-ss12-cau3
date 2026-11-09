package com.rikkei.microservice.orderservice.service.impl;

import com.rikkei.microservice.orderservice.dto.OrderRequestDTO;
import com.rikkei.microservice.orderservice.dto.OrderResponseDTO;
import com.rikkei.microservice.orderservice.dto.Product;
import com.rikkei.microservice.orderservice.entity.Order;
import com.rikkei.microservice.orderservice.event.OrderCreatedEvent;
import com.rikkei.microservice.orderservice.exception.ResourceNotFoundException;
import com.rikkei.microservice.orderservice.kafka.OrderProducer;
import com.rikkei.microservice.orderservice.repository.OrderRepository;
import com.rikkei.microservice.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final DiscoveryClient discoveryClient;
    private final RestClient restClient;
    private final OrderProducer orderProducer;

    @Override
    public OrderResponseDTO createOrder(OrderRequestDTO requestDTO) {
        if (requestDTO.getQuantity() == null || requestDTO.getQuantity() <= 0) {
            throw new IllegalArgumentException("Số lượng (quantity) phải lớn hơn 0");
        }

        // Lấy thông tin sản phẩm và đơn giá từ Product Service qua @LoadBalanced
        Product product = null;
        try {
            product = getProductFromProductService2(String.valueOf(requestDTO.getProductId()));
        } catch (Exception ex) {
            log.warn("Không thể lấy giá từ Product Service, sử dụng giá mặc định: {}", ex.getMessage());
        }

        BigDecimal unitPrice = (product != null && product.getPrice() != null)
                ? product.getPrice()
                : BigDecimal.valueOf(150.00);
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(requestDTO.getQuantity()));

        Order order = Order.builder()
                .customerId(requestDTO.getCustomerId())
                .productId(requestDTO.getProductId())
                .orderDate(LocalDateTime.now())
                .totalAmount(totalAmount)
                .build();

        // 1. Lưu đơn hàng vào cơ sở dữ liệu
        Order savedOrder = orderRepository.save(order);
        log.info("===> [Order Service] Đã lưu đơn hàng vào DB thành công với Order ID: {}", savedOrder.getId());

        // 2. Bắn sự kiện OrderCreatedEvent bất đồng bộ vào Kafka topic 'order-events'
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(savedOrder.getId())
                .customerId(savedOrder.getCustomerId())
                .productId(savedOrder.getProductId())
                .quantity(requestDTO.getQuantity())
                .totalAmount(savedOrder.getTotalAmount())
                .timestamp(savedOrder.getOrderDate())
                .build();

        orderProducer.sendOrderCreatedEvent(event);

        // 3. Phản hồi ngay lập tức cho client mà không chờ các tác vụ trừ kho hay gửi email hoàn tất
        return OrderResponseDTO.fromEntity(savedOrder);
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
