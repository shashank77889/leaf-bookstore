package com.leaf.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class PaymentService {
    final JdbcTemplate db; final StoreController store; final RazorpayClient gateway; final TransactionTemplate tx;
    PaymentService(JdbcTemplate db,StoreController store,RazorpayClient gateway,PlatformTransactionManager transactions){
        this.db=db;this.store=store;this.gateway=gateway;this.tx=new TransactionTemplate(transactions);
    }

    /**
     * Starts a Razorpay checkout in three steps so that no database lock is held while waiting on Razorpay:
     * 1. reserve stock and commit, 2. create the Razorpay order with no transaction open,
     * 3. save the Razorpay order id in a second short transaction.
     */
    public Map<String,Object> create(StoreController.Checkout checkout,Authentication auth){
        gateway.requireConfigured();
        var order=tx.execute(status->reserveForPayment(checkout,auth));
        if(order.get("PAYMENT").equals("RAZORPAY_TEST_PAID"))return Map.of("paid",true,"order",order);
        if(order.get("PROVIDER_ORDER_ID")!=null)return checkoutData(order);
        String id=(String)order.get("ID");
        int total=((Number)order.get("TOTAL")).intValue();
        String providerId;
        try{
            providerId=createProviderOrder(id,total);
        }catch(RuntimeException ex){
            // Nothing can have been paid yet, so give the books back straight away.
            tx.executeWithoutResult(status->releaseUnstarted(id));
            throw ex;
        }
        return tx.execute(status->attachProviderOrder(id,auth,providerId));
    }
    Map<String,Object> reserveForPayment(StoreController.Checkout checkout,Authentication auth){
        var order=locked((String)store.reserve(checkout,auth).get("ID"),auth);
        if(order.get("PAYMENT").equals("RAZORPAY_TEST_PAID"))return order;
        if(!order.get("STATUS").equals("PENDING_PAYMENT")||expired(order))throw StoreController.bad("This checkout has expired. Check payment status in My orders, then start another order.");
        return order;
    }
    String createProviderOrder(String id,int total){
        var remote=gateway.createOrder(total,id);
        String providerId=remote.path("id").asText();
        if(!providerId.startsWith("order_")||remote.path("amount").asInt()!=total||!remote.path("currency").asText().equals("INR"))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unexpected Razorpay order response.");
        return providerId;
    }
    Map<String,Object> attachProviderOrder(String id,Authentication auth,String providerId){
        var rows=db.queryForList("SELECT id FROM shop_orders WHERE id=?",id);
        // A parallel attempt for the same checkout failed and released the reservation.
        if(rows.isEmpty())throw StoreController.bad("This checkout was interrupted. Please try again.");
        var order=locked(id,auth);
        // If a parallel attempt already saved a Razorpay order, keep that one.
        if(order.get("PROVIDER_ORDER_ID")==null){
            db.update("UPDATE shop_orders SET provider_order_id=? WHERE id=?",providerId,id);
            order.put("PROVIDER_ORDER_ID",providerId);
        }
        return checkoutData(order);
    }
    /** Removes a reservation whose Razorpay order was never created, as if the checkout had not started. */
    void releaseUnstarted(String id){
        var rows=db.queryForList("SELECT * FROM shop_orders WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())return;
        var order=rows.getFirst();
        if(!order.get("STATUS").equals("PENDING_PAYMENT")||order.get("PROVIDER_ORDER_ID")!=null)return;
        restoreStock(id);
        db.update("DELETE FROM order_items WHERE order_id=?",id);
        db.update("DELETE FROM shop_orders WHERE id=?",id);
    }
    void restoreStock(String id){
        var lines=db.queryForList("SELECT product_id,quantity FROM order_items WHERE order_id=? ORDER BY product_id",id);
        for(var line:lines)db.update("UPDATE products SET stock=stock+? WHERE id=?",line.get("QUANTITY"),line.get("PRODUCT_ID"));
    }
    static boolean expired(Map<String,Object> order){return ((Timestamp)order.get("RESERVED_UNTIL")).toInstant().isBefore(Instant.now());}
    @Transactional
    public Map<String,Object> resume(String id,Authentication auth){
        gateway.requireConfigured();var order=locked(id,auth);
        if(order.get("PAYMENT").equals("RAZORPAY_TEST_PAID"))return Map.of("paid",true,"order",order);
        if(!order.get("STATUS").equals("PENDING_PAYMENT")||expired(order))throw StoreController.bad("This checkout has expired. Check payment status, then start a new order from your bag.");
        if(order.get("PROVIDER_ORDER_ID")==null)throw StoreController.bad("Payment was not started for this order. Return to your bag and check out again.");
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
        if(order.get("PROVIDER_ORDER_ID")==null){
            // The customer never received a Razorpay order, so nothing can have been paid: safe to release without asking Razorpay.
            if(!order.get("STATUS").equals("PENDING_PAYMENT"))return order;
            if(!expired(order))throw StoreController.bad("Payment checkout was not created.");
            restoreStock(id);
            db.update("UPDATE shop_orders SET status='EXPIRED',payment='RAZORPAY_EXPIRED' WHERE id=?",id);
            return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);
        }
        var payments=gateway.payments((String)order.get("PROVIDER_ORDER_ID")).path("items");
        // A previously failed attempt must not hide a later successful attempt.
        for(var payment:payments) if(payment.path("status").asText().equals("captured")){apply(order,payment);return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);}
        for(var payment:payments) if(payment.path("status").asText().equals("authorized")&&order.get("STATUS").equals("PENDING_PAYMENT")){apply(order,payment);return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);}
        if(order.get("STATUS").equals("PENDING_PAYMENT")&&expired(order)){
            // Never release stock on an API outage: this branch runs only after a successful provider check.
            restoreStock(id);
            db.update("UPDATE shop_orders SET status='EXPIRED',payment='RAZORPAY_EXPIRED' WHERE id=?",id);
        }
        return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);
    }
    /**
     * Lets the store admin settle an order whose payment was captured after its stock reservation ended.
     * "accept" takes the stock again and confirms the order; "refund" returns the money through Razorpay.
     */
    @Transactional
    public Map<String,Object> review(String id,String action){
        var order=locked(id,null);
        if(!order.get("STATUS").equals("PAYMENT_REVIEW"))throw StoreController.bad("This order is not waiting for payment review.");
        if(action.equals("accept"))acceptReviewed(id);else refundReviewed(order);
        return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);
    }
    void acceptReviewed(String id){
        var lines=db.queryForList("SELECT product_id,title,quantity FROM order_items WHERE order_id=? ORDER BY product_id",id);
        for(var line:lines){
            int stock=db.queryForObject("SELECT stock FROM products WHERE id=? FOR UPDATE",Integer.class,line.get("PRODUCT_ID"));
            if(stock<((Number)line.get("QUANTITY")).intValue())throw StoreController.bad(line.get("TITLE")+" no longer has enough copies in stock. Restock it or refund the payment.");
        }
        for(var line:lines)db.update("UPDATE products SET stock=stock-? WHERE id=?",line.get("QUANTITY"),line.get("PRODUCT_ID"));
        db.update("UPDATE shop_orders SET status='CONFIRMED',payment='RAZORPAY_TEST_PAID' WHERE id=?",id);
    }
    void refundReviewed(Map<String,Object> order){
        gateway.requireConfigured();
        String paymentId=(String)order.get("PROVIDER_PAYMENT_ID");
        int amount=((Number)order.get("TOTAL")).intValue();
        var payment=gateway.payment(paymentId);
        if(!payment.path("id").asText().equals(paymentId)||!payment.path("order_id").asText().equals(order.get("PROVIDER_ORDER_ID")))throw StoreController.bad("Payment does not match this order.");
        // An earlier attempt may have refunded at Razorpay without us recording it; never refund twice.
        boolean alreadyRefunded=payment.path("status").asText().equals("refunded")||payment.path("amount_refunded").asInt()>=amount;
        if(!alreadyRefunded){
            var refund=gateway.refund(paymentId,amount);
            if(!refund.path("payment_id").asText().equals(paymentId)||refund.path("amount").asInt()!=amount)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Unexpected Razorpay refund response. Check the payment in the Razorpay dashboard before retrying.");
        }
        db.update("UPDATE shop_orders SET status='REFUNDED',payment='RAZORPAY_TEST_REFUNDED' WHERE id=?",order.get("ID"));
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

