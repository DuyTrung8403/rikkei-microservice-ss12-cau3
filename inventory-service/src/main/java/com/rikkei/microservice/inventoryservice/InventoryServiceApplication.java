package com.rikkei.microservice.inventoryservice;

import com.rikkei.microservice.inventoryservice.entity.Inventory;
import com.rikkei.microservice.inventoryservice.repository.InventoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;

@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }

    /**
     * Khởi tạo dữ liệu tồn kho mẫu cho các sản phẩm (productId: 1, 2) để test trừ kho
     */
    @Bean
    public CommandLineRunner initInventoryData(InventoryRepository inventoryRepository) {
        return args -> {
            if (inventoryRepository.count() == 0) {
                inventoryRepository.save(Inventory.builder()
                        .productId(1L)
                        .productName("Laptop Dell XPS 13")
                        .stockQuantity(100)
                        .unitPrice(BigDecimal.valueOf(1500.00))
                        .build());

                inventoryRepository.save(Inventory.builder()
                        .productId(2L)
                        .productName("iPhone 15 Pro Max")
                        .stockQuantity(50)
                        .unitPrice(BigDecimal.valueOf(1200.00))
                        .build());

                System.out.println("===> [INVENTORY-SERVICE] Đã khởi tạo dữ liệu kho mẫu thành công (productId: 1 -> stock: 100, productId: 2 -> stock: 50)");
            }
        };
    }
}
