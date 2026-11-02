package com.leaf.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class PaymentReconciler {
    private static final Logger log=LoggerFactory.getLogger(PaymentReconciler.class);
    final JdbcTemplate db;final PaymentService payments;final RazorpayClient gateway;
    PaymentReconciler(JdbcTemplate db,PaymentService payments,RazorpayClient gateway){this.db=db;this.payments=payments;this.gateway=gateway;}
    @Scheduled(fixedDelayString="${store.razorpay.poll-ms:60000}",initialDelayString="${store.razorpay.poll-ms:60000}")
    public void refresh(){
        if(!gateway.configured())return;
        var rows=db.queryForList("SELECT id FROM shop_orders WHERE status IN ('PENDING_PAYMENT','EXPIRED') AND provider_order_id IS NOT NULL ORDER BY CASE WHEN status='PENDING_PAYMENT' THEN 0 ELSE 1 END, created_at DESC LIMIT 100");
        for(var row:rows)try{payments.reconcile((String)row.get("ID"),null);}catch(Exception ex){log.warn("Payment reconciliation deferred for order {}",row.get("ID"));}
    }
}

