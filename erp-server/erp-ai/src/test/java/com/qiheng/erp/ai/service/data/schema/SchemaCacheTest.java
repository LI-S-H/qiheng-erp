package com.qiheng.erp.ai.service.data.schema;

import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 缓存单测：命中、过期、清理。
 *
 * @author Li
 * @since 2026-10-21
 */
class SchemaCacheTest {

    private AuthorizedSchemaSnapshot snapshot(String text) {
        return AuthorizedSchemaSnapshot.of(text, java.util.List.of(), java.util.Map.of());
    }

    @Test
    void missingKeyReturnsEmpty() {
        assertThat(new SchemaCache(300).get("none")).isEmpty();
    }

    @Test
    void storedValueIsReturned() {
        SchemaCache cache = new SchemaCache(300);
        cache.put("product", snapshot("schema-text"));

        assertThat(cache.get("product")).map(AuthorizedSchemaSnapshot::schemaText).contains("schema-text");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void expiredEntryIsTreatedAsMissing() throws InterruptedException {
        SchemaCache cache = new SchemaCache(0);
        cache.put("product", snapshot("schema-text"));
        Thread.sleep(5);

        assertThat(cache.get("product")).isEmpty();
    }

    @Test
    void clearRemovesEverything() {
        SchemaCache cache = new SchemaCache(300);
        cache.put("product", snapshot("a"));
        cache.put("warehouse", snapshot("b"));

        cache.clear();

        assertThat(cache.size()).isZero();
        assertThat(cache.get("product")).isEmpty();
    }
}
