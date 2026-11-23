package com.company.retail.infrastructure.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 营业日历 MyBatis Mapper（表 core.holidays，R06 §4.8）。
 */
public interface BusinessCalendarMapper {

    /** 指定日期是否命中节假日表。 */
    @Select("SELECT COUNT(*) FROM core.holidays WHERE holiday_date = #{date}")
    int countHolidays(@Param("date") LocalDate date);

    /** 装载节假日（幂等：同一日期不重复插入）。 */
    @Insert("INSERT INTO core.holidays (holiday_date, name) VALUES (#{date}, #{name}) "
            + "ON CONFLICT (holiday_date) DO NOTHING")
    int insertHoliday(@Param("date") LocalDate date, @Param("name") String name);

    /** 区间内的节假日。 */
    @Select("SELECT holiday_date FROM core.holidays "
            + "WHERE holiday_date BETWEEN #{from} AND #{to} ORDER BY holiday_date")
    List<LocalDate> holidaysBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
