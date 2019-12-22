package com.springboot.redis.lock.springbootredislock.controller;

import org.apache.commons.lang.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
public class TestController1 {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private Redisson redisson;


    @RequestMapping("/test01")
    public String test() {
        //假设有两个线程同一时刻执行完这一句代码，查询出来的库存都为30
        String productStock = stringRedisTemplate.opsForValue().get("product_stock");
        if (StringUtils.isBlank(productStock)) {
            return "productStock is empty";
        }
        int stock = Integer.parseInt(productStock);
        if (stock > 0) {
            int currentStock = stock - 1;
            //然后两个线程都用各自拿到的剩余库存30件减掉1件，也就是两个线程减完都还剩29件，然后将29件更新回redis中
            //显然这就存在资源共享(超卖的情况)问题了，两个线程正常减库存，应该剩余28件才对。
            stringRedisTemplate.opsForValue().set("product_stock", Integer.toString(currentStock));
            System.out.println("减库存成功,当前库存剩余" + currentStock);
        } else {
            System.out.println("库存不足...");
        }
        return "success";
    }

    @RequestMapping("/test02")
    public String test02() {
        //锁住减库存这一块代码，使得多个线程进来只能有一个线程进去，其他线程必须在外面等待。
        //必须注意的是： 这种解决方案只能使用在单体架构中，但是现在公司基本不会使用单体架构，不可能只有一个实例，
        //一般都会集群分布式部署多个实例，如果同步块使用到分布式部署环境下，那还是一样会存在之前的问题，原因是：
        //synchronized是JVM进程内部的锁，集群部署肯定是有多个JVM进程。
        synchronized (this) {
            String productStock = stringRedisTemplate.opsForValue().get("product_stock");
            if (StringUtils.isBlank(productStock)) {
                return "productStock is empty";
            }
            int stock = Integer.parseInt(productStock);
            if (stock > 0) {
                int currentStock = stock - 1;
                stringRedisTemplate.opsForValue().set("product_stock", Integer.toString(currentStock));
                System.out.println("减库存成功,当前库存剩余" + currentStock);
            } else {
                System.out.println("库存不足...");
            }
        }
        return "success";
    }

    @RequestMapping("/test03")
    public String test03() {
        //对每个商品对应上一把锁
        String lockKey = "product_001";

        try {
            //setnx key value
//            Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "wsh");

            //下面两条语句之间如果发生宕机，会导致超时时间没有设置，也会存在问题
//            Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "wsh");
//            stringRedisTemplate.expire(lockKey,30, TimeUnit.SECONDS);

            //同时设置值以及超时时间，这样两者之间就是原子操作
            Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "wsh", 30, TimeUnit.SECONDS);

            //如果redis中已经存在lockKey中，直接返回，只有一个线程能够执行下面的减库存操作。
            if (!result) {
                return "当前人数过多，请稍后重试！";
            }

            String productStock = stringRedisTemplate.opsForValue().get("product_stock");
            if (StringUtils.isBlank(productStock)) {
                return "productStock is empty";
            }
            int stock = Integer.parseInt(productStock);
            if (stock > 0) {
                int currentStock = stock - 1;
                stringRedisTemplate.opsForValue().set("product_stock", Integer.toString(currentStock));
                System.out.println("减库存成功,当前库存剩余" + currentStock);
            } else {
                System.out.println("库存不足...");
            }
        } finally {
            //解锁、释放锁
            stringRedisTemplate.delete(lockKey);
        }

        return "success";

        /**
         * 存在问题：
         * 1. 执行try里面代码的时候发生宕机，这样redis中的lockKey就会永远存在，也就是说锁永远没有被放开，这样以后所有线程都不会弄那个抢购该商品；
         */
    }

    /**
     * 解决问题： 主从架构中由于业务执行时间大于锁的超时时间，导致线程1在线程2还没执行完，将不属于线程1的锁给删掉了，导致锁永久失效。
     * 分线程自动延时，定时器判断当前锁是否为当前线程所有，如果是刷新lockKey过期时间(大概每(30s * 1 / 3 =)10s执行一次)
     */
    @RequestMapping("/test04")
    public String test04() {
        //对每个商品对应上一把锁
        String lockKey = "product_001";
        String clientId = UUID.randomUUID().toString();

        try {
            //同时设置值以及超时时间，这样两者之间就是原子操作
            Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, clientId, 30, TimeUnit.SECONDS);

            //如果redis中已经存在lockKey中，直接返回，只有一个线程能够执行下面的减库存操作。
            if (!result) {
                return "当前人数过多，请稍后重试！";
            }

            String productStock = stringRedisTemplate.opsForValue().get("product_stock");
            if (StringUtils.isBlank(productStock)) {
                return "productStock is empty";
            }
            int stock = Integer.parseInt(productStock);
            if (stock > 0) {
                int currentStock = stock - 1;
                stringRedisTemplate.opsForValue().set("product_stock", Integer.toString(currentStock));
                System.out.println("减库存成功,当前库存剩余" + currentStock);
            } else {
                System.out.println("库存不足...");
            }
        } finally {
            //解锁、释放锁
            //只释放自己加的锁
            if (clientId.equals(stringRedisTemplate.opsForValue().get(lockKey))) {
                stringRedisTemplate.delete(lockKey);
            }
        }

        return "success";
    }


    @RequestMapping("/test05")
    public String test05() {
        String lockKey = "product_001";
        RLock lock = redisson.getLock(lockKey);
        try {
            lock.lock(30, TimeUnit.SECONDS);

            String productStock = stringRedisTemplate.opsForValue().get("product_stock");
            if (StringUtils.isBlank(productStock)) {
                return "productStock is empty";
            }
            int stock = Integer.parseInt(productStock);
            if (stock > 0) {
                int currentStock = stock - 1;
                stringRedisTemplate.opsForValue().set("product_stock", Integer.toString(currentStock));
                System.out.println("减库存成功,当前库存剩余" + currentStock);
            } else {
                System.out.println("库存不足...");
            }
        } finally {
            lock.unlock();
        }
        return "success";
    }

}
