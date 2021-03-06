// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.cosmos.cassandra.example;

import com.datastax.oss.driver.api.core.CqlSession;
import com.github.javafaker.Faker;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example class which will demonstrate handling rate limiting using retry policy, and client side load balancing using
 * Cosmos DB multi-master capability
 */
public class UserProfile {

    public static final int NUMBER_OF_THREADS = 40;
    public static final int NUMBER_OF_WRITES_PER_THREAD = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(UserProfile.class);

    Queue<String> docIDs = new ConcurrentLinkedQueue<String>();
    AtomicInteger exceptionCount = new AtomicInteger(0);
    AtomicLong insertCount = new AtomicLong(0);
    AtomicInteger recordCount = new AtomicInteger(0);
    AtomicLong totalLatency = new AtomicLong(0);
    AtomicLong totalReadLatency = new AtomicLong(0);

    /**
     * Runs a load test.
     *
     * @param keyspace            Keyspace name.
     * @param table               Table name.
     * @param repository          Reference to a {@link UserRepository user repository}.
     * @param userProfile         Reference to a {@link UserProfile user profile}.
     * @param preparedStatement   A prepared statement.
     * @param finalQuery          A final query.
     * @param noOfThreads         Number of threads to create.
     * @param noOfWritesPerThread Number of writes per thread.
     *
     * @throws InterruptedException If the load test is interrupted.
     */
    public void loadTest(
        final String keyspace,
        final String table,
        final UserRepository repository,
        final UserProfile userProfile,
        final String preparedStatement,
        final String finalQuery,
        final int noOfThreads,
        final int noOfWritesPerThread) throws InterruptedException {

        final Faker faker = new Faker();
        final ExecutorService es = Executors.newCachedThreadPool();

        for (int i = 1; i <= noOfThreads; i++) {
            final Runnable task = () -> {
                for (int j = 1; j <= noOfWritesPerThread; j++) {
                    final UUID guid = java.util.UUID.randomUUID();
                    final String strGuid = guid.toString();
                    this.docIDs.add(strGuid);
                    try {
                        final String name = faker.name().lastName();
                        final String city = faker.address().city();
                        userProfile.recordCount.incrementAndGet();
                        final long startTime = System.currentTimeMillis();
                        repository.insertUser(preparedStatement, guid.toString(), name, city);
                        final long endTime = System.currentTimeMillis();
                        final long duration = (endTime - startTime);
                        System.out.print("insert duration time millis: " + duration + "\n");
                        this.totalLatency.getAndAdd(duration);
                        userProfile.insertCount.incrementAndGet();
                    } catch (final Exception e) {
                        userProfile.exceptionCount.incrementAndGet();
                        System.out.println("Exception: " + e);
                    }
                }
            };
            es.execute(task);
        }

        es.shutdown();

        final boolean finished = es.awaitTermination(5, TimeUnit.MINUTES);

        if (finished) {

            Thread.sleep(5000);
            final long latency = (this.totalLatency.get() / this.insertCount.get());

            //lets look at latency for reads in local region by reading all the records just written
            final int readcount = NUMBER_OF_THREADS * NUMBER_OF_WRITES_PER_THREAD;
            long noOfUsersInTable = 0;

            noOfUsersInTable = repository.selectUserCount(finalQuery);

            for (final String id : this.docIDs) {
                final long startTime = System.currentTimeMillis();
                repository.selectUser(id, keyspace, table);
                final long endTime = System.currentTimeMillis();
                final long duration = (endTime - startTime);
                System.out.print("read duration time millis: " + duration + "\n");
                this.totalReadLatency.getAndAdd(duration);
            }

            System.out.println("count of inserts attempted: " + userProfile.recordCount);
            System.out.println("count of users in table: " + noOfUsersInTable);

            final long readLatency = (this.totalReadLatency.get() / readcount);
            System.out.print("Average write Latency: " + latency + "\n");
            System.out.println("Average read latency: " + readLatency);
            System.out.println("Finished executing all threads.");
        }
    }

    /**
     * Entry point to the application.
     *
     * @param args an array of command line arguments.
     */
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification = "False alarm on Java 11+")
    public static void main(final String[] args) {

        final String keyspace = "azure_cosmos_cassandra_driver_4_examples";
        final UserProfile userProfile = new UserProfile();
        final String table = "user" + System.getProperty("azure.cosmos.cassandra.run-id", "");

        try (CqlSession session = CqlSession.builder().build()) {

            final UserRepository repository = new UserRepository(session);

            // Create keyspace and table in database

            repository.createKeyspace("CREATE KEYSPACE IF NOT EXISTS " + keyspace
                + " WITH REPLICATION = { 'class' : 'NetworkTopologyStrategy', 'datacenter1' : 1 }");

            repository.dropTable("DROP TABLE IF EXISTS " + keyspace + "." + table);

            repository.createTable("CREATE TABLE " + keyspace + "." + table + " ("
                + "user_id text PRIMARY KEY,"
                + "user_name text,"
                + "user_bcity text)");

            Thread.sleep(5_000);

            System.out.println("Done creating " + keyspace + "." + table + " table...");
            LOGGER.info("inserting records...");

            //Setup load test queries

            final String loadTestPreparedStatement = "INSERT INTO " + keyspace + "." + table + " ("
                + "user_bcity,"
                + "user_id,"
                + "user_name) "
                + "VALUES (?,?,?)";

            final String loadTestFinalSelectQuery = "SELECT COUNT(*) as coun FROM " + keyspace + "." + table + "";

            // Run Load Test - Insert rows into user table

            userProfile.loadTest(
                keyspace,
                table,
                repository,
                userProfile,
                loadTestPreparedStatement,
                loadTestFinalSelectQuery,
                NUMBER_OF_THREADS,
                NUMBER_OF_WRITES_PER_THREAD);

        } catch (final Throwable error) {
            System.out.println("Main Exception " + error);
            System.exit(1);
        }
    }
}
