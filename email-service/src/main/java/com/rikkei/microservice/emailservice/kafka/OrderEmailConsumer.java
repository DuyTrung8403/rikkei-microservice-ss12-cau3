package com.rikkei.microservice.emailservice.kafka;

import com.rikkei.microservice.emailservice.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderEmailConsumer {

    /**
     * Consumer lắng nghe sự kiện order-events với consumer group độc lập: 'email-service-group'.
     */
    @KafkaListener(topics = "order-events", groupId = "email-service-group")
    public void handleOrderCreated(OrderCreatedEvent event,
                                   @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                   @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
                                   @Header(value = KafkaHeaders.OFFSET, required = false) Long offset) {
        log.info("===> [EMAIL-SERVICE CONSUMER] Đã nhận sự kiện từ topic 'order-events' [partition={}, offset={}, key={}]: {}",
                partition, offset, key, event);

        // Giả lập xử lý gửi email xác nhận và hóa đơn đơn hàng cho khách hàng
        sendOrderConfirmationEmail(event);
    }

    private void sendOrderConfirmationEmail(OrderCreatedEvent event) {
        log.info("===> [EMAIL-SERVICE] Đang gửi email xác nhận cho Customer ID: {} | Order ID: {} | Tổng tiền: {} VND",
                event.getCustomerId(), event.getOrderId(), event.getTotalAmount());
        System.out.println("==================================================");
        System.out.println("[EMAIL-SERVICE] GỬI EMAIL ĐẶT HÀNG THÀNH CÔNG");
        System.out.println("Người nhận (Customer ID): " + event.getCustomerId());
        System.out.println("Mã đơn hàng: #" + event.getOrderId());
        System.out.println("Mã sản phẩm: " + event.getProductId() + " (Số lượng: " + event.getQuantity() + ")");
        System.out.println("Tổng thanh toán: " + event.getTotalAmount());
        System.out.println("Thời gian: " + event.getTimestamp());
        System.out.println("==================================================");
    }
}
