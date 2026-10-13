// 경로: src/main/java/com/myide/backend/service/saferun/RunQueue.java
package com.myide.backend.service.saferun;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 동시에 돌 수 있는 실행 개수를 제한하는 대기열.
 *
 * 운영 서버가 2GB 한 대다. Spring Boot 실행 하나가 gradle 과 앱을 합쳐 700MB 넘게 쓰므로,
 * 두 개가 동시에 돌면 서버 자체가 메모리 부족으로 죽는다. 그래서 서버 전체에서 정한
 * 개수만 돌리고 나머지는 차례를 기다리게 한다.
 *
 * 자리는 빌드할 때뿐 아니라 <b>앱이 떠 있는 동안 내내</b> 쥐고 있어야 한다. 메모리를
 * 쓰는 것은 떠 있는 앱도 마찬가지이기 때문이다.
 */
public class RunQueue {

    private final Semaphore slots;

    public RunQueue(int concurrency) {
        // 먼저 기다린 사람이 먼저 들어간다.
        this.slots = new Semaphore(Math.max(1, concurrency), true);
    }

    /**
     * 자리가 날 때까지 기다린다.
     *
     * @param cancelled 기다리는 사람이 그만뒀는지(중지를 눌렀거나 창을 닫았는지)
     * @param onWait    바로 들어가지 못해 기다리게 됐을 때 한 번 불린다
     * @return 자리를 얻었으면 true. 기다리다 그만뒀으면 false(자리를 얻지 않았으므로 release 하지 말 것)
     */
    public boolean acquire(BooleanSupplier cancelled, Runnable onWait) throws InterruptedException {
        if (slots.tryAcquire()) {
            return true;
        }

        onWait.run();

        while (!cancelled.getAsBoolean()) {
            if (slots.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }

        return false;
    }

    public void release() {
        slots.release();
    }
}
