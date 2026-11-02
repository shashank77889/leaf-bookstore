package com.leaf.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class PaymentService {
    final JdbcTemplate db; final StoreController store; final RazorpayClient gateway;
    PaymentService(JdbcTemplate db,StoreController store,RazorpayClient gateway){this.db=db;this.store=store;this.gateway=gateway;}
    @Transactional
    public Map<String,Object> create(StoreController.Checkout checkout,Authentication auth){
        gateway.requireConfigured();
        var order=store.reserve(checkout,auth);
        String id=(String)order.get("ID");
        order=locked(id,auth);
        if(order.get("PAYMENT").equals("RAZORPAY_TEST_PAID"))return Map.of("paid",true,"order",order);
        if(!order.get("STATUS").equals("PENDING_PAYMENT")||((Timestamp)order.get("RESERVED_UNTIL")).toInstant().isBefore(Instant.now()))throw StoreController.bad("This checkout has expired. Check payment status in My orders, then start another order.");
        if(order.get("PROVIDER_ORDER_ID")==null){
            var remote=gateway.createOrder(((Number)order.get("TOTAL")).intValue(),id);
            String providerId=remote.path("id").asText();
            if(!providerId.startsWith("order_")||remote.path("amount").asInt()!=((Number)order.get("TOTAL")).intValue()||!remote.path("currency").asText().equals("INR"))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unexpected Razorpay order response.");
            db.update("UPDATE shop_orders SET provider_order_id=? WHERE id=?",providerId,id);
            order.put("PROVIDER_ORDER_ID",providerId);
        }
        return checkoutData(order);
    }
    @Transactional
    public Map<String,Object> resume(String id,Authentication auth){
        gateway.requireConfigured();var order=locked(id,auth);
        if(order.get("PAYMENT").equals("RAZORPAY_TEST_PAID"))return Map.of("paid",true,"order",order);
        if(!order.get("STATUS").equals("PENDING_PAYMENT")||((Timestamp)order.get("RESERVED_UNTIL")).toInstant().isBefore(Instant.now()))throw StoreController.bad("This checkout has expired. Check payment status, then start a new order from your bag.");
        return checkoutData(order);
    }
    Map<String,Object> checkoutData(Map<String,Object> order){return Map.of("paid",false,"id",order.get("ID"),"providerOrderId",order.get("PROVIDER_ORDER_ID"),"amount",order.get("TOTAL"),"currency","INR","keyId",gateway.keyId(),"mode","test");}
    Map<String,Object> locked(String id,Authentication auth){
        var rows=db.queryForList("SELECT * FROM shop_orders WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw StoreController.missing();var order=rows.getFirst();
        if(auth!=null&&!Objects.equals(order.get("ACCOUNT_ID"),store.account(auth).get("ID")))throw StoreController.missing();
        if(order.get("PAYMENT").equals("DEMO_PAID"))throw StoreController.bad("This is a simulated order, not a Razorpay payment.");
        return order;
    }
    @Transactional
    public Map<String,Object> verify(PaymentController.Verification v,Authentication auth){
        gateway.requireConfigured();var order=locked(v.id(),auth);
        String storedId=(String)order.get("PROVIDER_ORDER_ID");
        if(storedId==null||!storedId.equals(v.razorpay_order_id())||!gateway.checkoutSignature(storedId,v.razorpay_payment_id(),v.razorpay_signature()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Payment signature verification failed. No order was confirmed.");
        apply(order,gateway.payment(v.razorpay_payment_id()));
        return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",v.id());
    }
    @Transactional
    public Map<String,Object> reconcile(String id,Authentication auth){
        gateway.requireConfigured();var order=locked(id,auth);
        if(order.get("PAYMENT").equals("RAZORPAY_TEST_PAID")||order.get("STATUS").equals("PAYMENT_REVIEW"))return order;
        if(order.get("PROVIDER_ORDER_ID")==null)throw StoreController.bad("Payment checkout was not created.");
        var payments=gateway.payments((String)order.get("PROVIDER_ORDER_ID")).path("items");
        // A previously failed attempt must not hide a later successful attempt.
        for(var payment:payments) if(payment.path("status").asText().equals("captured")){apply(order,payment);return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);}
        for(var payment:payments) if(payment.path("status").asText().equals("authorized")&&order.get("STATUS").equals("PENDING_PAYMENT")){apply(order,payment);return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);}
        if(order.get("STATUS").equals("PENDING_PAYMENT")&&((Timestamp)order.get("RESERVED_UNTIL")).toInstant().isBefore(Instant.now())){
            // Never release stock on an API outage: this branch runs only after a successful provider check.
            var lines=db.queryForList("SELECT product_id,quantity FROM order_items WHERE order_id=? ORDER BY product_id",id);
            for(var line:lines)db.update("UPDATE products SET stock=stock+? WHERE id=?",line.get("QUANTITY"),line.get("PRODUCT_ID"));
            db.update("UPDATE shop_orders SET status='EXPIRED',payment='RAZORPAY_EXPIRED' WHERE id=?",id);
        }
        return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);
    }
    void apply(Map<String,Object> order,JsonNode payment){
        int amount=((Number)order.get("TOTAL")).intValue();
        if(!payment.path("order_id").asText().equals(order.get("PROVIDER_ORDER_ID"))||payment.path("amount").asInt()!=amount||!payment.path("currency").asText().equals("INR"))
            throw StoreController.bad("Payment amount, currency, or order does not match.");
        String paymentId=payment.path("id").asText();gateway.safeId(paymentId);
        if(paymentId.isBlank())throw StoreController.bad("Missing payment reference.");
        if(order.get("PAYMENT").equals("RAZORPAY_TEST_PAID")){
            if(!paymentId.equals(order.get("PROVIDER_PAYMENT_ID")))throw StoreController.bad("An alternative payment cannot replace the confirmed payment.");
            return;
        }
        if(payment.path("status").asText().equals("authorized")&&order.get("STATUS").equals("PENDING_PAYMENT")){
            // Only capture while inventory remains reserved. On uncertain responses, retry reconciliation.
            payment=gateway.capture(paymentId,amount);
            if(!payment.path("id").asText().equals(paymentId)||!payment.path("order_id").asText().equals(order.get("PROVIDER_ORDER_ID"))||payment.path("amount").asInt()!=amount||!payment.path("currency").asText().equals("INR"))throw StoreController.bad("Unexpected capture response.");
        }
        if(!payment.path("status").asText().equals("captured")||!payment.path("captured").asBoolean())return;
        boolean reserved=order.get("STATUS").equals("PENDING_PAYMENT");
        db.update("UPDATE shop_orders SET status=?,payment=?,provider_payment_id=? WHERE id=?",reserved?"CONFIRMED":"PAYMENT_REVIEW",reserved?"RAZORPAY_TEST_PAID":"RAZORPAY_TEST_REVIEW",paymentId,order.get("ID"));
    }
}

