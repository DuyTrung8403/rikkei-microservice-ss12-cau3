package com.rikkei.microservice.productservice.service.impl;

import com.rikkei.microservice.productservice.config.RedisConfig;
import com.rikkei.microservice.productservice.dto.ProductRequestDTO;
import com.rikkei.microservice.productservice.dto.ProductResponseDTO;
import com.rikkei.microservice.productservice.entity.Product;
import com.rikkei.microservice.productservice.exception.OutOfStockException;
import com.rikkei.microservice.productservice.exception.ResourceNotFoundException;
import com.rikkei.microservice.productservice.repository.ProductRepository;
import com.rikkei.microservice.productservice.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final RedissonClient redissonClient;

    @Override
    public ProductResponseDTO createProduct(ProductRequestDTO requestDTO) {
        Product product = Product.builder()
                .name(requestDTO.getName())
                .price(requestDTO.getPrice())
                .stockQuantity(requestDTO.getStockQuantity())
                .build();

        Product savedProduct = productRepository.save(product);
        return ProductResponseDTO.fromEntity(savedProduct);
    }

    @Override
    @Cacheable(value = RedisConfig.PRODUCT_CACHE, key = "#id")
    public ProductResponseDTO getProductById(Long id) {
        log.info("===> [Database Query] Truy vấn thông tin sản phẩm từ cơ sở dữ liệu với ID: {}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));
        return ProductResponseDTO.fromEntity(product);
    }

    @Override
    public List<ProductResponseDTO> getAllProducts() {
        return productRepository.findAll().stream()
                .map(ProductResponseDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @CacheEvict(value = RedisConfig.PRODUCT_CACHE, key = "#id")
    public ProductResponseDTO updateProduct(Long id, ProductRequestDTO requestDTO) {
        log.info("===> [Cache Evict] Đang cập nhật sản phẩm và xóa cache ID: {}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));

        product.setName(requestDTO.getName());
        product.setPrice(requestDTO.getPrice());
        product.setStockQuantity(requestDTO.getStockQuantity());

        Product updatedProduct = productRepository.save(product);
        return ProductResponseDTO.fromEntity(updatedProduct);
    }

    @Override
    @CacheEvict(value = RedisConfig.PRODUCT_CACHE, key = "#id")
    public void deleteProduct(Long id) {
        log.info("===> [Cache Evict] Đang xóa sản phẩm và xóa cache ID: {}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));
        productRepository.delete(product);
    }

    /**
     * Khóa phân tán Redisson xử lý Flash Sale:
     * - Khóa theo key: lock:product:{id}
     * - Kiểm tra tồn kho và trừ số lượng an toàn
     * - Giải phóng khóa trong finally
     * - Tự động xóa cache của sản phẩm sau khi trừ kho thành công
     */
    @Override
    @CacheEvict(value = RedisConfig.PRODUCT_CACHE, key = "#id")
    public ProductResponseDTO reduceStockWithLock(Long id, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            quantity = 1;
        }

        String lockKey = "lock:product:" + id;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Chờ tối đa 5 giây để lấy khóa, giữ khóa tối đa 10 giây (watchdog hoặc lease time)
            boolean isLocked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                log.warn("===> [Flash Sale] Không thể lấy được khóa {} do hệ thống đang quá tải", lockKey);
                throw new OutOfStockException("Hệ thống đang bận, vui lòng thử lại sau!");
            }

            log.info("===> [Flash Sale] Thread {} đã giành được khóa {} thành công", Thread.currentThread().getName(), lockKey);

            // Tiến hành kiểm tra sản phẩm và số lượng tồn kho trong DB
            Product product = productRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));

            int currentStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            if (currentStock < quantity) {
                log.warn("===> [Flash Sale] Sản phẩm ID: {} chỉ còn tồn kho: {}, yêu cầu: {}. Báo hết hàng!",
                        id, currentStock, quantity);
                throw new OutOfStockException("Sản phẩm đã hết hàng");
            }

            // Trừ tồn kho và lưu vào DB
            int newStock = currentStock - quantity;
            product.setStockQuantity(newStock);
            Product updatedProduct = productRepository.save(product);

            log.info("===> [Flash Sale Thành công] Giảm tồn kho sản phẩm ID: {} ({} -> {}), người mua đặt thành công",
                    id, currentStock, newStock);

            return ProductResponseDTO.fromEntity(updatedProduct);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tiến trình bị gián đoạn trong khi chờ khóa", e);
        } finally {
            // BẮT BUỘC: Đảm bảo giải phóng khóa trong khối finally
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("===> [Flash Sale] Đã giải phóng khóa {} an toàn", lockKey);
            }
        }
    }
}
