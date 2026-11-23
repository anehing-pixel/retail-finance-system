package com.company.retail.infrastructure.persistence;

import com.company.platform.calendar.BusinessCalendar;
import com.company.retail.infrastructure.mapper.BusinessCalendarMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 营业日历 MyBatis 实现（机制同构 floorplan 的营业日历；表 core.holidays，R06 §4.8）。
 *
 * <p>周六周日非营业日 + core.holidays 里的日本节假日；数据由 S2 的年度假日子作业维护
 * （与 floorplan 同一节假日口径，直接沿用其维护作业范式）。</p>
 */
public class MyBatisBusinessCalendar implements BusinessCalendar {

    private final BusinessCalendarMapper mapper;

    public MyBatisBusinessCalendar(BusinessCalendarMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        int hits = mapper.countHolidays(date);
        return hits == 0;
    }

    @Override
    public LocalDate plusBusinessDays(LocalDate from, int n) {
        LocalDate cursor = from;
        int remaining = Math.abs(n);
        int step = n >= 0 ? 1 : -1;
        int guard = 0;
        while (remaining > 0 && guard++ < 3660) {
            cursor = cursor.plusDays(step);
            if (isBusinessDay(cursor)) {
                remaining--;
            }
        }
        return cursor;
    }

    /** 年度假日装载（供维护作业调用；幂等，重复装载不产生重复行）。 */
    public void loadHolidays(Set<LocalDate> dates, String name) {
        for (LocalDate d : dates) {
            mapper.insertHoliday(d, name);
        }
    }

    /** 已登记假日（测试与对拍用）。 */
    public Set<LocalDate> holidaysBetween(LocalDate from, LocalDate to) {
        return new HashSet<>(mapper.holidaysBetween(from, to));
    }
}
