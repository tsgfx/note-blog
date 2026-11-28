package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingVectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsExactly1024Dimensions() throws Exception {
        double[] values = new double[1024];
        var node = mapper.valueToTree(values);
        assertThat(EmbeddingVector.require1024(node, mapper)).hasSize(1024);
    }

    @Test
    void rejectsWrongDimensions() throws Exception {
        var node = mapper.valueToTree(List.of(1.0, 2.0));
        assertThatThrownBy(() -> EmbeddingVector.require1024(node, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1024");
    }
}
