package com.rikkei.microservice.orderservice.kafka;

import com.rikkei.microservice.orderservice.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProducer {

    public static final String TOPIC = "order-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Bắn sự kiện OrderCreatedEvent bất đồng bộ vào topic 'order-events'.
     * Dùng orderId làm message key để đảm bảo thứ tự các event của cùng một đơn hàng.
     */
    public CompletableFuture<SendResult<String, Object>> sendOrderCreatedEvent(OrderCreatedEvent event) {
        String key = String.valueOf(event.getOrderId());
        log.info("===> [KAFKA PRODUCER] Đang gửi OrderCreatedEvent tới topic '{}': key={}, orderId={}, productId={}, quantity={}",
                TOPIC, key, event.getOrderId(), event.getProductId(), event.getQuantity());

        return kafkaTemplate.send(TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("===> [KAFKA SUCCESS] Đã gửi sự kiện thành công! Topic: {}, Partition: {}, Offset: {}",
                                TOPIC,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("===> [KAFKA ERROR] Gửi sự kiện thất bại tới topic '{}': {}", TOPIC, ex.getMessage());
                    }
                });
    }
}
