package com.rikkei.microservice.productservice.service.impl;

import com.rikkei.microservice.productservice.config.RedisConfig;
import com.rikkei.microservice.productservice.dto.ProductRequestDTO;
import com.rikkei.microservice.productservice.dto.ProductResponseDTO;
import com.rikkei.microservice.productservice.entity.Product;
import com.rikkei.microservice.productservice.exception.ResourceNotFoundException;
import com.rikkei.microservice.productservice.repository.ProductRepository;
import com.rikkei.microservice.productservice.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

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
}
