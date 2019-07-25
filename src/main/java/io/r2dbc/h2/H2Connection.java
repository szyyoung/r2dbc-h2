/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.h2;

import io.r2dbc.h2.client.Client;
import io.r2dbc.h2.codecs.Codecs;
import io.r2dbc.h2.util.Assert;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.SQLException;
import java.util.function.Function;

import static io.r2dbc.spi.IsolationLevel.READ_COMMITTED;
import static io.r2dbc.spi.IsolationLevel.READ_UNCOMMITTED;
import static io.r2dbc.spi.IsolationLevel.REPEATABLE_READ;
import static io.r2dbc.spi.IsolationLevel.SERIALIZABLE;
import static org.h2.engine.Constants.LOCK_MODE_OFF;
import static org.h2.engine.Constants.LOCK_MODE_READ_COMMITTED;
import static org.h2.engine.Constants.LOCK_MODE_TABLE;

/**
 * An implementation of {@link Connection} for connecting to an H2 database.
 */
public final class H2Connection implements Connection {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Client client;

    private final Codecs codecs;

    H2Connection(Client client, Codecs codecs) {
        this.client = Assert.requireNonNull(client, "client must not be null");
        this.codecs = Assert.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public Mono<Void> beginTransaction() {
        return useTransactionStatus(inTransaction -> {
            if (!inTransaction) {
                this.client.disableAutoCommit();
            } else {
                this.logger.debug("Skipping begin transaction because already in one");
            }
            return Mono.empty();
        })
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create);
    }

    @Override
    public Mono<Void> close() {
        return this.client.close();
    }

    @Override
    public Mono<Void> commitTransaction() {
        return useTransactionStatus(inTransaction -> {
            if (inTransaction) {
                this.client.execute("COMMIT");
                this.client.enableAutoCommit();
            } else {
                this.logger.debug("Skipping commit transaction because no transaction in progress.");
            }

            return Mono.empty();
        })
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create);
    }

    @Override
    public H2Batch createBatch() {
        return new H2Batch(this.client, this.codecs);
    }

    @Override
    public Mono<Void> createSavepoint(String name) {
        Assert.requireNonNull(name, "name must not be null");

        return useTransactionStatus(inTransaction -> {
            if (inTransaction) {
                this.client.execute(String.format("SAVEPOINT %s", name));
            } else {
                this.logger.debug("Skipping savepoint because no transaction in progress.");
            }
            return Mono.empty();
        })
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create);
    }

    @Override
    public H2Statement createStatement(String sql) {
        return new H2Statement(this.client, this.codecs, sql);
    }

    @Override
    public Mono<Void> releaseSavepoint(String name) {
        Assert.requireNonNull(name, "name must not be null");

        return useTransactionStatus(inTransaction -> {
            if (inTransaction) {
                this.client.execute(String.format("RELEASE SAVEPOINT %s", name));
            } else {
                this.logger.debug("Skipping release savepoint because no transaction in progress.");
            }

            return Mono.empty();
        })
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create);
    }

    @Override
    public Mono<Void> rollbackTransaction() {
        return useTransactionStatus(inTransaction -> {
            if (inTransaction) {
                this.client.execute("ROLLBACK");
                this.client.enableAutoCommit();
            } else {
                this.logger.debug("Skipping rollback because no transaction in progress.");
            }
            return Mono.empty();
        })
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create);
    }

    @Override
    public Mono<Void> rollbackTransactionToSavepoint(String name) {
        Assert.requireNonNull(name, "name must not be null");

        return useTransactionStatus(inTransaction -> {
            if (inTransaction) {
                this.client.execute(String.format("ROLLBACK TO SAVEPOINT %s", name));
            } else {
                this.logger.debug("Skipping rollback to savepoint because no transaction in progress.");
            }

            return Mono.empty();
        })
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create);
    }

    @Override
    public Mono<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
        Assert.requireNonNull(isolationLevel, "isolationLevel must not be null");

        return Mono.<Void>fromRunnable(() -> {
            this.client.execute(getTransactionIsolationLevelQuery(isolationLevel));
        })
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create);
    }

    private static String getTransactionIsolationLevelQuery(IsolationLevel isolationLevel) {
        if (READ_COMMITTED == isolationLevel) {
            return String.format("SET LOCK_MODE %d", LOCK_MODE_READ_COMMITTED);
        } else if (READ_UNCOMMITTED == isolationLevel) {
            return String.format("SET LOCK_MODE %d", LOCK_MODE_OFF);
        } else if (REPEATABLE_READ == isolationLevel || SERIALIZABLE == isolationLevel) {
            return String.format("SET LOCK_MODE %d", LOCK_MODE_TABLE);
        } else {
            throw new IllegalArgumentException(String.format("Invalid isolation level %s", isolationLevel));
        }
    }

    private Mono<Void> useTransactionStatus(Function<Boolean, Publisher<?>> f) {
        return Flux.defer(() -> f.apply(this.client.inTransaction()))
            .onErrorMap(SQLException.class, H2DatabaseExceptionFactory::create)
            .then();
    }

}
