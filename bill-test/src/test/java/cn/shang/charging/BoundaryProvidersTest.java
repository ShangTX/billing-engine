package cn.shang.charging;

import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundaryProvidersTest {

    @Test
    void freeRangeEdges_returnsOnlyNearestBoundary() {
        BoundaryProvider provider = BoundaryProviders.freeRangeEdges(List.of(
                range("2026-01-01T09:00", "2026-01-01T10:00"),
                range("2026-01-01T12:00", "2026-01-01T13:00")
        ));

        assertEquals(time("2026-01-01T09:00"),
                provider.nextBoundary(time("2026-01-01T08:30"), time("2026-01-01T14:00")));
        assertEquals(time("2026-01-01T10:00"),
                provider.nextBoundary(time("2026-01-01T09:30"), time("2026-01-01T14:00")));
        assertEquals(time("2026-01-01T12:00"),
                provider.nextBoundary(time("2026-01-01T10:30"), time("2026-01-01T14:00")));
    }

    private static FreeTimeRange range(String begin, String end) {
        return FreeTimeRange.builder()
                .beginTime(time(begin))
                .endTime(time(end))
                .build();
    }

    private static LocalDateTime time(String value) {
        return LocalDateTime.parse(value);
    }
}
