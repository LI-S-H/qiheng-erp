package com.qiheng.erp.ai.service.data.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 缓存单测：命中、过期、清理。
 *
 * @author Li
 * @since 2026-10-21
 */
class SchemaCacheTest {

    @Test
    void missingKeyReturnsEmpty() {
        assertThat(new SchemaCache(300).get("none")).isEmpty();
    }

    @Test
    void storedValueIsReturned() {
        SchemaCache cache = new SchemaCache(300);
        cache.put("product", "schema-text");

        assertThat(cache.get("product")).contains("schema-text");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void expiredEntryIsTreatedAsMissing() throws InterruptedException {
        SchemaCache cache = new SchemaCache(0);
        cache.put("product", "schema-text");
        Thread.sleep(5);

        assertThat(cache.get("product")).isEmpty();
    }

    @Test
    void clearRemovesEverything() {
        SchemaCache cache = new SchemaCache(300);
        cache.put("product", "a");
        cache.put("warehouse", "b");

        cache.clear();

        assertThat(cache.size()).isZero();
        assertThat(cache.get("product")).isEmpty();
    }
}
