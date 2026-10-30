package com.myide.backend.service;

import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerServiceZombieFilterTest {

    private Container container(String name, String image, Map<String, String> labels) {
        Container c = mock(Container.class);
        when(c.getNames()).thenReturn(new String[]{name});
        when(c.getImage()).thenReturn(image);
        when(c.getLabels()).thenReturn(labels);
        return c;
    }

    @Test
    void 이미지_이름으로_만든_컨테이너를_찾는다() {
        // 예전 필터는 컨테이너 이름("/happy_turing")을 봐서 이걸 놓쳤다.
        assertThat(DockerService.isIdeContainer(container("/happy_turing", "ide-execution-env", Map.of()))).isTrue();
        assertThat(DockerService.isIdeContainer(container("/x", "ide-execution-env:latest", null))).isTrue();
    }

    @Test
    void 이미지를_다시_빌드해도_표시로_찾는다() {
        assertThat(DockerService.isIdeContainer(container("/x", "sha256:abc", Map.of("myide.managed", "true")))).isTrue();
    }

    @Test
    void 다른_컨테이너는_건드리지_않는다() {
        assertThat(DockerService.isIdeContainer(container("/mysql", "mysql:8", Map.of()))).isFalse();
        assertThat(DockerService.isIdeContainer(container("/x", "ide-execution-env-old", null))).isFalse();
    }
}
