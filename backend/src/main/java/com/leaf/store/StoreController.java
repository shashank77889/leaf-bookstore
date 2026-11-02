package com.leaf.store;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class StoreController {
    private final JdbcTemplate db; private final PasswordEncoder encoder;
    StoreController(JdbcTemplate db, PasswordEncoder encoder) { this.db=db; this.encoder=encoder; }
    public record Credentials(@NotBlank @Email @Size(max=160) String email, @NotBlank @Size(min=8,max=72) String password, @Size(max=80) String name) {}
    public record Item(@Positive long id, @Min(1) @Max(20) int quantity) {}
    public record Checkout(@NotEmpty @Size(max=30) List<@NotNull @Valid Item> items, @NotBlank @Size(max=80) String requestKey,
        @NotBlank @Size(max=80) String recipient, @NotBlank @Size(max=300) String address,
        @NotBlank @Size(max=80) String city, @Pattern(regexp="[1-9][0-9]{5}") @NotNull String postcode,
        @Pattern(regexp="success|decline") @NotNull String outcome) {}
    public record ProductInput(@NotBlank @Size(max=120) String title, @NotBlank @Size(max=100) String author,
        @Pattern(regexp="Fiction|Personal growth|Nature|Lifestyle|Design") @NotNull String category,
        @NotBlank @Size(max=1500) String description, @Min(100) @Max(10000000) int price,
        @Min(0) @Max(100000) int stock, @Pattern(regexp="#[a-fA-F0-9]{6}") @NotNull String color) {}

    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"header",token.getHeaderName()); }
    @GetMapping("/products") List<Map<String,Object>> products() { return db.queryForList("SELECT * FROM products WHERE active=TRUE ORDER BY id"); }
    @GetMapping("/auth/me") Object me(Authentication auth) { return auth==null ? Map.of("authenticated",false) : publicUser(account(auth)); }
    @PostMapping("/auth/register") @Transactional
    Object register(@Valid @RequestBody Credentials c, HttpServletRequest req, HttpServletResponse res) {
        if(c.name()==null || c.name().isBlank()) throw bad("Please enter your name.");
        try { db.update("INSERT INTO accounts(name,email,password_hash,role) VALUES(?,?,?,?)",c.name().trim(),c.email().trim().toLowerCase(Locale.ROOT),encoder.encode(c.password()),"CUSTOMER"); }
        catch(DuplicateKeyException ex) { throw bad("This email already has an account. Please sign in."); }
        return signIn(c,req,res);
    }
    @PostMapping("/auth/login") Object login(@Valid @RequestBody Credentials c, HttpServletRequest req, HttpServletResponse res) { return signIn(c,req,res); }
    Object signIn(Credentials c,HttpServletRequest req,HttpServletResponse res) {
        var matches=db.queryForList("SELECT * FROM accounts WHERE email=?",c.email().trim().toLowerCase(Locale.ROOT));
        if(matches.isEmpty() || !encoder.matches(c.password(),(String)matches.getFirst().get("PASSWORD_HASH")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Email or password is incorrect.");
        var a=matches.getFirst();
        req.getSession(); req.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(a.get("EMAIL"),null,List.of(new SimpleGrantedAuthority("ROLE_"+a.get("ROLE")))));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,req,res);
        return publicUser(a);
    }
    Map<String,Object> account(Authentication auth) { return db.queryForMap("SELECT * FROM accounts WHERE email=?",auth.getName()); }
    Map<String,Object> publicUser(Map<String,Object> a) { return Map.of("authenticated",true,"id",a.get("ID"),"name",a.get("NAME"),"email",a.get("EMAIL"),"role",a.get("ROLE")); }
    @GetMapping("/orders") Object orders(Authentication auth) { return orderList("WHERE account_id=?",account(auth).get("ID")); }
    List<Map<String,Object>> orderList(String where,Object... params) {
        var orders=db.queryForList("SELECT * FROM shop_orders "+where+" ORDER BY created_at DESC",params);
        for(var o:orders) o.put("ITEMS",db.queryForList("SELECT title,quantity,price FROM order_items WHERE order_id=?",o.get("ID")));
        return orders;
    }
    @PostMapping("/orders") @Transactional
    Object checkout(@Valid @RequestBody Checkout c, Authentication auth) {
        return createOrder(c,auth,false);
    }
    @Transactional
    public Map<String,Object> reserve(Checkout c, Authentication auth) { return createOrder(c,auth,true); }
    Map<String,Object> createOrder(Checkout c, Authentication auth, boolean gateway) {
        long user=((Number)account(auth).get("ID")).longValue();
        db.queryForMap("SELECT id FROM accounts WHERE id=? FOR UPDATE",user);
        var existing=db.queryForList("SELECT * FROM shop_orders WHERE account_id=? AND request_key=?",user,c.requestKey());
        if(!existing.isEmpty()) {
            var old=existing.getFirst();
            boolean wasGateway=!old.get("PAYMENT").equals("DEMO_PAID");
            if(wasGateway!=gateway) throw bad("This checkout was already started with another payment method. Update your bag to start a new order.");
            return old;
        }
        if(!gateway && c.outcome().equals("decline")) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"Demo payment declined. Your cart is saved; try the successful payment option.");
        var quantities=new TreeMap<Long,Integer>();
        for(var i:c.items()) quantities.merge(i.id(),i.quantity(),Integer::sum);
        var selected=new ArrayList<Map<String,Object>>(); long subtotal=0;
        for(var e:quantities.entrySet()) {
            if(e.getValue()>20) throw bad("Maximum 20 copies per book.");
            var rows=db.queryForList("SELECT * FROM products WHERE id=? AND active=TRUE FOR UPDATE",e.getKey());
            if(rows.isEmpty()) throw bad("A book in your cart is no longer available. Refresh your cart.");
            var p=rows.getFirst();
            if(((Number)p.get("STOCK")).intValue()<e.getValue()) throw bad(p.get("TITLE")+" does not have enough copies in stock.");
            subtotal+=(long)((Number)p.get("PRICE")).intValue()*e.getValue();
            selected.add(p);
        }
        if(subtotal>Integer.MAX_VALUE-5900) throw bad("Order total exceeds the allowed amount.");
        int shipping=subtotal>=99900?0:5900; String id=UUID.randomUUID().toString();
        db.update("INSERT INTO shop_orders(id,account_id,request_key,total,shipping,status,payment,recipient,address,city,postcode) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id,user,c.requestKey(),subtotal+shipping,shipping,gateway?"PENDING_PAYMENT":"CONFIRMED",gateway?"RAZORPAY_PENDING":"DEMO_PAID",c.recipient().trim(),c.address().trim(),c.city().trim(),c.postcode());
        for(var p:selected) {
            long pid=((Number)p.get("ID")).longValue(); int quantity=quantities.get(pid);
            db.update("INSERT INTO order_items(order_id,product_id,title,quantity,price) VALUES(?,?,?,?,?)",id,pid,p.get("TITLE"),quantity,p.get("PRICE"));
            db.update("UPDATE products SET stock=stock-? WHERE id=?",quantity,pid);
        }
        if(gateway) db.update("UPDATE shop_orders SET reserved_until=DATEADD('MINUTE',30,CURRENT_TIMESTAMP) WHERE id=?",id);
        return db.queryForMap("SELECT * FROM shop_orders WHERE id=?",id);
    }
    @GetMapping("/admin/products") Object adminProducts() { return db.queryForList("SELECT * FROM products ORDER BY id"); }
    @PostMapping("/admin/products") Object create(@Valid @RequestBody ProductInput p) {
        db.update("INSERT INTO products(title,author,category,description,price,stock,color) VALUES(?,?,?,?,?,?,?)",p.title(),p.author(),p.category(),p.description(),p.price(),p.stock(),p.color());
        return Map.of("message","Book added.");
    }
    @PutMapping("/admin/products/{id}") Object update(@PathVariable long id,@Valid @RequestBody ProductInput p) {
        if(db.update("UPDATE products SET title=?,author=?,category=?,description=?,price=?,stock=?,color=? WHERE id=?",p.title(),p.author(),p.category(),p.description(),p.price(),p.stock(),p.color(),id)==0) throw missing();
        return Map.of("message","Book updated.");
    }
    @PatchMapping("/admin/products/{id}/visibility") Object visibility(@PathVariable long id,@RequestBody Map<String,Boolean> body) {
        if(body.get("active")==null) throw bad("Choose visibility.");
        if(db.update("UPDATE products SET active=? WHERE id=?",body.get("active"),id)==0) throw missing();
        return Map.of("message","Visibility updated.");
    }
    @GetMapping("/admin/orders") Object adminOrders() { return orderList(""); }
    @PatchMapping("/admin/orders/{id}") @Transactional
    Object status(@PathVariable String id,@RequestBody Map<String,String> body) {
        var rows=db.queryForList("SELECT status FROM shop_orders WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw missing();
        String current=(String)rows.getFirst().get("STATUS"), next=body.get("status");
        if(!Objects.equals(next,current) && !(current.equals("CONFIRMED") && "SHIPPED".equals(next)) && !(current.equals("SHIPPED") && "DELIVERED".equals(next))) throw bad("Orders move from confirmed to shipped to delivered.");
        db.update("UPDATE shop_orders SET status=? WHERE id=?",next,id); return Map.of("message","Order updated.");
    }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Item not found."); }
}


