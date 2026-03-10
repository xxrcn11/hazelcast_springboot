package com.bt.hz.domain.sessions;

import com.bt.hz.domain.sessions.models.SYSSE015I;
import com.hazelcast.map.EntryProcessor;

import java.util.Map;

/**
 * M_SYSSE015I 맵에 대해 시간대별 세션 카운트를 원자적으로 갱신하는 EntryProcessor.
 *
 * <p>MSYSSE015IScheduler에서 호출되며, stdYmd + "_" + stdHour 를 키로 사용합니다.
 * <ul>
 *   <li>해당 키가 없으면 SYSSE015I를 신규 생성 후 totCnt = 1 설정</li>
 *   <li>해당 키가 있으면 기존 cnt 에 count 를 더함</li>
 * </ul>
 */
public class MSYSSE015IEntryProcessor implements EntryProcessor<String, SYSSE015I, Void> {

    private static final long serialVersionUID = 1L;

    private final String stdYmd;
    private final String stdHour;
    private final int count;

    public MSYSSE015IEntryProcessor(String stdYmd, String stdHour, int count) {
        this.stdYmd = stdYmd;
        this.stdHour = stdHour;
        this.count = count;
    }

    @Override
    public Void process(Map.Entry<String, SYSSE015I> entry) {
        SYSSE015I current = entry.getValue();
        if (current == null) {
            // 일치하는 데이터가 없으면 신규 생성, totCnt = 1
            SYSSE015I newEntry = new SYSSE015I();
            newEntry.setStdYmd(stdYmd);
            newEntry.setStdHour(stdHour);
            newEntry.setCnt(1);
            entry.setValue(newEntry);
        } else {
            // 일치하는 값이 있으면 기존 cnt 에 count 덧셈
            current.setCnt(current.getCnt() + count);
            entry.setValue(current);
        }
        return null;
    }
}
