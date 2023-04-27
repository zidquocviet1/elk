package com.example.elkstack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executor;

@EnableScheduling
@EnableAsync
@SpringBootApplication
public class ELKStackApplication {

    public static void main(String[] args) {
        SpringApplication.run(ELKStackApplication.class, args);
    }

}

@Configuration
class ExecutorConfig {
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ElkStack-");
        executor.initialize();
        return executor;
    }
}


@RestController
@RequestMapping("/v1/products")
record ProductController(ProductService productService) {
    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    @GetMapping
    public ResponseEntity<List<Product>> getAll() {
        logger.debug("Client request get product list");
        return ResponseEntity.ok(productService.getAll());
    }
}

@Service
@ConditionalOnExpression("${my.project.service.product.enabled:true}")
record ProductService(ProductRepository productRepository) {
    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    public List<Product> getAll() {
        logger.info("Get product list from database");
        return productRepository.getAll();
    }

    /**
     * Fixed rate (aka Fixed Window): Execute the task in specific time interval, if the task has been completed sooner than the interval
     * then job scheduler will wait for the rest time and then schedule it again. If the task has been completed longer than
     * the interval then job scheduler will execute the new task after previous task complete.
     * <br></br>
     * For example (interval = 10s):
     * <table border="1">
     *   <tr>
     *     <td> 1st run: 12:00:00 </td> <td> -> finished in 12:00:06 </td> <td> -> will wait for 4 seconds </td>
     *   </tr>
     *   <tr>
     *     <td> 2nd run: 12:00:10 </td> <td> -> finished in 12:00:30 </td> <td> -> will start new task immediately </td>
     *   </tr>
     * </table>
     * <br></br>
     * Fixed delay (aka Sliding Window): Execute the task in specific time interval after the task is completed
     * even if the previous task completed sooner or later than time interval.
     * <br></br>
     * For example (interval = 10s):
     * <table border="1">
     *   <tr>
     *     <td> 1st run: 12:00:00 </td> <td> -> finished in 12:00:06 </td> <td> -> will wait for 10 seconds </td>
     *   </tr>
     *   <tr>
     *     <td> 2nd run: 12:00:16 </td> <td> -> finished in 12:00:30 </td> <td> -> it still hasn't started immediately, must be wait for 10s </td>
     *   </tr>
     * </table>
     */
    @Scheduled(initialDelay = 1000L, fixedRateString = "${my.project.service.generate.job.ts}")
    public void generateDummyProduct() {
        logger.info("Begin generate new products to store in memory...");

        var name = "Generated Product - " + System.currentTimeMillis();
        var price = new SecureRandom().nextLong();

        // Task scheduled with fixedDelay or fixedRate must be the outbound of the long-running task.
        // However, the long-running can be run in seperated instances in the same thread with task executor.
        logger.info("Request add new product with name={} and price={}", name, price);

        productRepository.simulateLongRunningTask();

        // This is a new task with Async annotation. This will make spring boot run that task in another thread
        // which was defined by the user otherwise it will provide default task executor.
        productRepository.addProduct(name, price);
    }
}

@Repository
class ProductRepository {
    private static final Logger logger = LoggerFactory.getLogger(ProductRepository.class);
    private static final List<Product> PRODUCTS = new ArrayList<>();

    static {
        PRODUCTS.add(
                new Product(
                        UUID.randomUUID().toString(),
                        "Macbook Pro 16 inch",
                        63000000
                )
        );
        PRODUCTS.add(
                new Product(
                        UUID.randomUUID().toString(),
                        "Iphone 14 Pro Max",
                        53000000
                )
        );
    }

    public List<Product> getAll() {
        return new ArrayList<>(PRODUCTS);
    }

    @Async
    public void addProduct(String name, double price) {
        PRODUCTS.add(
                new Product(
                        UUID.randomUUID().toString(),
                        name,
                        price
                )
        );
        logger.info("Add new product successfully");
    }

//    @Async // Uncomment this line to see the problem
    public void simulateLongRunningTask() {
        // This is long-running task
        try {
            var randomSec = new Random().nextInt(1, 20);
            logger.info("Simulate the long-running task with thread by sleep: {}s", randomSec);
            Thread.sleep(randomSec * 1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        logger.info("Long-running task was stopped after a few seconds. Already inserted new product");
        // End of long-running task
    }
}

record Product(String id, String name, double price) {
}