package com.example.aitask.messaging;

import org.springframework.amqp.core.*;

/** Dedicated P1 topology; no TTL/DLX loss path or automatic parking consumer. */
public record TaskQueueNames(String prefix) {
    public TaskQueueNames {
        if (prefix == null || !prefix.matches("p1\\.[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("Rabbit queue prefix must be a dedicated p1.* name");
        }
    }
    public String exchange() { return prefix + ".exchange"; }
    public String work() { return prefix + ".tasks"; }
    public String parking() { return prefix + ".parking"; }
    public Declarables declarations() {
        var exchange = new DirectExchange(exchange(), true, false);
        var work = new Queue(work(), true, false, false);
        var parking = new Queue(parking(), true, false, false);
        return new Declarables(exchange, work, parking,
                BindingBuilder.bind(work).to(exchange).with("task"),
                BindingBuilder.bind(parking).to(exchange).with("park"));
    }
}
