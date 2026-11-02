package com.leaf.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SeedData implements CommandLineRunner {
    private final JdbcTemplate db; private final PasswordEncoder encoder; private final String adminPassword;
    SeedData(JdbcTemplate db, PasswordEncoder encoder, @Value("${store.admin-password}") String adminPassword) {
        this.db=db; this.encoder=encoder; this.adminPassword=adminPassword;
    }
    @Override public void run(String... args) {
        if (db.queryForObject("SELECT COUNT(*) FROM accounts WHERE email='admin@leaf.local'", Integer.class)==0)
            db.update("INSERT INTO accounts(name,email,password_hash,role) VALUES(?,?,?,?)", "Store admin", "admin@leaf.local", encoder.encode(adminPassword), "ADMIN");
        if (db.queryForObject("SELECT COUNT(*) FROM products", Integer.class)>0) return;
        add("The Quiet Hours", "Mira Sen", "Fiction", "A small coastal town. An unexpected letter. A tender story about the lives we build and the ones we leave behind. A fictional title created for this demo bookstore.", 449, 18, "#243c63");
        add("Make Room", "Oliver Reed", "Personal growth", "A thoughtful guide to making space for the things that matter: focused work, meaningful routines, and a little more everyday calm. A fictional demo title.", 599, 12, "#bd482c");
        add("A Field Guide to Wonder", "Nora Ellis", "Nature", "Slow down and rediscover the extraordinary in the ordinary, from the changing sky to the small wilderness just outside your door. A fictional demo title.", 749, 8, "#436449");
        add("Small, Good Things", "Amelia Park", "Lifestyle", "Notes on simple food, thoughtful spaces, and finding joy in the everyday. An invitation to live a little more intentionally. A fictional demo title.", 399, 24, "#a7485e");
        add("Beyond the Blueprint", "Daniel Brooks", "Design", "Explore how useful, beautiful things come to life through observation, experimentation, and the courage to begin again. A fictional demo title.", 899, 6, "#d5ad35");
        add("Somewhere, Elsewhere", "Leah Morgan", "Fiction", "Two strangers cross paths on an overnight train, discovering that sometimes the longest journey is the one that takes us home. A fictional demo title.", 499, 15, "#5e4a86");
        add("The Everyday Mind", "Arjun Mehta", "Personal growth", "Practical reflections on attention, habits, and thinking clearly in a noisy world. Small ideas to return to, one day at a time. A fictional demo title.", 549, 10, "#316d76");
        add("Wild at Heart", "Freya Woods", "Nature", "A collection of stories from forests, mountains, and the people who care for them. For anyone who feels most themselves outdoors. A fictional demo title.", 649, 9, "#8a512f");
    }
    void add(String title,String author,String category,String description,int price,int stock,String color) {
        db.update("INSERT INTO products(title,author,category,description,price,stock,color) VALUES(?,?,?,?,?,?,?)",title,author,category,description,price*100,stock,color);
    }
}
