package com.likelion4thon.Meetdle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.likelion4thon.Meetdle.contract.BoundaryFixtures;
import com.likelion4thon.Meetdle.room.dto.CreateRoomRequest;
import com.likelion4thon.Meetdle.room.dto.CreateRoomRequest.DateRange;
import com.likelion4thon.Meetdle.room.dto.CreateRoomResponse;
import com.likelion4thon.Meetdle.room.invite.InviteCodeGenerator;
import com.likelion4thon.Meetdle.room.service.RoomService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

/** 초대 코드가 이미 쓰인 코드와 겹치면 새 코드를 다시 뽑는지 확인한다. */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
@Sql(scripts = "/fixtures/base-room.sql", config = @SqlConfig(encoding = "UTF-8"))
class InviteCodeCollisionTests {

    @MockitoSpyBean
    private InviteCodeGenerator generator;

    @Autowired
    private RoomService roomService;

    @Test
    void drawsAnotherCodeWhenTheFirstIsTaken() {
        // 1번째 방: AAAAAA / 2번째 방: AAAAAA(겹침) → BBBBBB
        doReturn("AAAAAA", "AAAAAA", "BBBBBB").when(generator).nextCode();
        CreateRoomRequest request = new CreateRoomRequest(null,
                new DateRange(LocalDate.of(2030, 1, 10), LocalDate.of(2030, 1, 12)), 4);

        CreateRoomResponse first = roomService.createRoom(BoundaryFixtures.HOST, request);
        CreateRoomResponse second = roomService.createRoom(BoundaryFixtures.HOST, request);

        assertThat(first.invite().code()).isEqualTo("AAAAAA");
        assertThat(second.invite().code()).isEqualTo("BBBBBB");
    }
}
