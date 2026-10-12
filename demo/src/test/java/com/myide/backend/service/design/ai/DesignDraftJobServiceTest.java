package com.myide.backend.service.design.ai;

import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.dto.design.v2.DesignDraftJobResponse;
import com.myide.backend.dto.design.v2.DesignDraftRequest;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.ErdV2;
import com.myide.backend.service.NotificationService;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면을 떠나도 초안이 끝까지 만들어지고, 끝난 것을 알리는지 확인한다.
 *
 * 실제 AI 는 부르지 않는다. 여기서 지키려는 것은 작업의 상태 흐름이다.
 */
class DesignDraftJobServiceTest {

    private static final DesignDraftRequest REQUEST =
            new DesignDraftRequest("중고 거래", null, "", null);

    private static final DesignModelV2 MODEL = new DesignModelV2(
            DesignModelV2.CURRENT_SCHEMA_VERSION, null,
            List.of(), List.of(), List.of(), List.of(), ErdV2.empty());

    private DesignDraftService draftService;
    private NotificationService notificationService;
    private DesignDraftJobService service;

    @BeforeEach
    void setUp() {
        draftService = Mockito.mock(DesignDraftService.class);
        notificationService = Mockito.mock(NotificationService.class);
        service = new DesignDraftJobService(
                draftService, Mockito.mock(DesignDoctorService.class), notificationService);
    }

    private DesignDraftJobResponse waitUntilFinished() throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            DesignDraftJobResponse job = service.get(1L, "ws");
            if (job != null && !"RUNNING".equals(job.status())) {
                return job;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("작업이 끝나지 않았습니다.");
    }

    @Test
    @DisplayName("다 만들면 결과를 담고 알림을 남긴다")
    void finishesAndNotifies() throws Exception {
        Mockito.when(draftService.generateDetail(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(MODEL);

        service.start(1L, "ws", REQUEST);
        DesignDraftJobResponse job = waitUntilFinished();

        assertThat(job.status()).isEqualTo("DONE");
        assertThat(job.model()).isSameAs(MODEL);

        Mockito.verify(notificationService, Mockito.timeout(2000)).notifyUser(
                Mockito.eq(1L), Mockito.eq("ws"), Mockito.eq(NotificationType.SYSTEM),
                Mockito.anyString(), Mockito.anyString(), Mockito.eq("/design?workspaceId=ws"));
    }

    @Test
    @DisplayName("실패하면 이유를 그대로 담는다")
    void keepsFailureReason() throws Exception {
        Mockito.when(draftService.generateSkeleton(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지금은 못 씁니다."));

        service.start(1L, "ws", REQUEST);
        DesignDraftJobResponse job = waitUntilFinished();

        assertThat(job.status()).isEqualTo("FAILED");
        assertThat(job.errorMessage()).isEqualTo("지금은 못 씁니다.");

        Mockito.verify(notificationService, Mockito.timeout(2000)).notifyUser(
                Mockito.eq(1L), Mockito.eq("ws"), Mockito.eq(NotificationType.ERROR),
                Mockito.anyString(), Mockito.eq("지금은 못 씁니다."), Mockito.anyString());
    }

    @Test
    @DisplayName("만드는 중에 또 눌러도 AI 를 두 번 부르지 않는다")
    void doesNotStartTwice() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Mockito.when(draftService.generateSkeleton(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(invocation -> {
                    release.await();
                    return MODEL;
                });

        service.start(1L, "ws", REQUEST);
        assertThat(service.start(1L, "ws", REQUEST).status()).isEqualTo("RUNNING");

        release.countDown();
        waitUntilFinished();

        Mockito.verify(draftService, Mockito.times(1)).generateSkeleton(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("버리면 남지 않는다")
    void discardRemoves() throws Exception {
        service.start(1L, "ws", REQUEST);
        waitUntilFinished();

        service.discard(1L, "ws");

        assertThat(service.get(1L, "ws")).isNull();
    }
}
