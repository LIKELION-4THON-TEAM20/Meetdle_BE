package com.likelion4thon.Meetdle.planning.station;

import com.likelion4thon.Meetdle.contract.SelectedMeeting.Station;
import com.likelion4thon.Meetdle.planning.subway.SubwayData;
import com.likelion4thon.Meetdle.planning.subway.SubwayData.CatalogStation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버가 켜질 때 공공데이터 역 목록을 station 테이블에 채운다 (이미 있으면 최신 값으로 덮어씀).
 * 역 ID는 이름으로 계산한 고정 UUID라서, 몇 번을 켜도 같은 역은 같은 ID를 유지한다.
 * → 사람들이 저장해 둔 출발역·목적지(member_input)가 깨지지 않는다.
 */
@Component
public class StationCatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StationCatalogSeeder.class);

    private final SubwayData subwayData;
    private final StationRepository stationRepository;

    public StationCatalogSeeder(SubwayData subwayData, StationRepository stationRepository) {
        this.subwayData = subwayData;
        this.stationRepository = stationRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (CatalogStation station : subwayData.stations()) {
            stationRepository.upsert(
                    new Station(station.stationId(), station.name(), station.lines(), station.lat(), station.lng()),
                    "MEETDLE:" + station.key());
        }
        log.info("지하철 역 카탈로그 {}개를 station 테이블에 반영했어요", subwayData.stations().size());
    }
}
