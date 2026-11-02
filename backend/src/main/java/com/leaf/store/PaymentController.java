package com.leaf.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    final RazorpayClient gateway;final PaymentService payments;final JdbcTemplate db;
    PaymentController(RazorpayClient gateway,PaymentService payments,JdbcTemplate db){this.gateway=gateway;this.payments=payments;this.db=db;}
    public record Verification(@NotBlank @Size(max=36) String id,
        @NotBlank @Pattern(regexp="order_[A-Za-z0-9]+") String razorpay_order_id,
        @NotBlank @Pattern(regexp="pay_[A-Za-z0-9]+") String razorpay_payment_id,
        @NotBlank @Pattern(regexp="[a-fA-F0-9]{64}") String razorpay_signature) {}
    @GetMapping("/config") Object config(){return Map.of("provider","razorpay","mode","test","configured",gateway.configured());}
    @PostMapping("/orders") Object create(@Valid @RequestBody StoreController.Checkout checkout,Authentication auth){return payments.create(checkout,auth);}
    @GetMapping("/orders/{id}/checkout") Object resume(@PathVariable String id,Authentication auth){return payments.resume(id,auth);}
    @PostMapping("/verify") Object verify(@Valid @RequestBody Verification verification,Authentication auth){return payments.verify(verification,auth);}
    @PostMapping("/orders/{id}/refresh") Object refresh(@PathVariable String id,Authentication auth){return payments.reconcile(id,auth);}
    @PostMapping("/webhook") Object webhook(@RequestBody String raw,@RequestHeader(value="X-Razorpay-Signature",required=false) String signature){
        if(!gateway.webhookSignature(raw,signature))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid webhook signature.");
        var event=gateway.parse(raw);
        String type=event.path("event").asText();
        if(Set.of("payment.captured","payment.authorized","order.paid").contains(type)){
            String providerId=event.path("payload").path("payment").path("entity").path("order_id").asText();
            if(providerId.isBlank())providerId=event.path("payload").path("order").path("entity").path("id").asText();
            var rows=db.queryForList("SELECT id FROM shop_orders WHERE provider_order_id=?",providerId);
            if(!rows.isEmpty())payments.reconcile((String)rows.getFirst().get("ID"),null);
        }
        return Map.of("received",true);
    }
}
