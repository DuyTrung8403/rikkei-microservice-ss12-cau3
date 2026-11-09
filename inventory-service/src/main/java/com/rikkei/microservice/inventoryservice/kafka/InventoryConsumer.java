package com.rikkei.microservice.inventoryservice.kafka;

import com.rikkei.microservice.inventoryservice.entity.Inventory;
import com.rikkei.microservice.inventoryservice.event.OrderCreatedEvent;
import com.rikkei.microservice.inventoryservice.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryConsumer {

    private final InventoryRepository inventoryRepository;

    /**
     * Consumer lắng nghe sự kiện order-events với consumer group độc lập: 'inventory-service-group'.
     */
    @KafkaListener(topics = "order-events", groupId = "inventory-service-group")
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event,
                                   @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                   @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                                   @Header(value = KafkaHeaders.OFFSET, required = false) Long offset) {
        log.info("===> [INVENTORY-SERVICE CONSUMER] Đã nhận sự kiện từ topic 'order-events' [partition={}, offset={}, key={}]: {}",
                partition, offset, key, event);

        // Khấu trừ số lượng tồn kho theo productId
        Long productId = event.getProductId();
        Integer quantityToDeduct = event.getQuantity() != null ? event.getQuantity() : 0;

        inventoryRepository.findByProductId(productId).ifPresentOrElse(inventory -> {
            int currentStock = inventory.getStockQuantity() != null ? inventory.getStockQuantity() : 0;
            int newStock = Math.max(0, currentStock - quantityToDeduct);
            inventory.setStockQuantity(newStock);
            inventoryRepository.save(inventory);

            log.info("===> [INVENTORY-SERVICE] Đã trừ kho thành công cho Product ID: {} | Tồn cũ: {} -> Tồn mới: {}",
                    productId, currentStock, newStock);
            System.out.println("==================================================");
            System.out.println("[INVENTORY-SERVICE] CẬP NHẬT TỒN KHO THÀNH CÔNG");
            System.out.println("Mã đơn hàng: #" + event.getOrderId());
            System.out.println("Mã sản phẩm: " + productId + " (" + inventory.getProductName() + ")");
            System.out.println("Số lượng đã trừ: " + quantityToDeduct);
            System.out.println("Tồn kho còn lại: " + newStock);
            System.out.println("==================================================");
        }, () -> {
            log.warn("===> [INVENTORY-SERVICE] Không tìm thấy sản phẩm có Product ID: {} trong bảng kho", productId);
        });
    }
}
