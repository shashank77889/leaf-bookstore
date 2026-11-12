package com.leaf.store;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

/** Admin-only actions for orders in PAYMENT_REVIEW. Access is restricted by the /api/admin/** rule in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/orders")
public class PaymentReviewController {
    final PaymentService payments;
    PaymentReviewController(PaymentService payments){this.payments=payments;}
    public record Decision(@NotNull @Pattern(regexp="accept|refund") String action) {}
    @PostMapping("/{id}/review") Object review(@PathVariable String id,@Valid @RequestBody Decision decision){return payments.review(id,decision.action());}
}
