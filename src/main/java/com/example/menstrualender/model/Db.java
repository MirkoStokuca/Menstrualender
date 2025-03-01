package com.example.menstrualender.model;

import com.example.menstrualender.model.db.Util;
import java.sql.*;
import java.time.LocalDate;

public class Db {
    Util util;


    protected String SQL_STATS = """
            with base as (
                select
                    cyc_id
                     , date(cyc_start) as this_cycle
                     , lag(cyc_start) over (order by date(cyc_start)) as last_cycle
                from cycle
                order by date(cyc_start)
            ), diff as (
                select
                    this_cycle
                     , last_cycle
                     , julianday(this_cycle) - julianday(last_cycle) as cycle_length
                from base
                where last_cycle is not null
            ), cycle_avg as (
                select round(avg(cycle_length),0) as cycle_avg_days
                from diff
            )
            """
    ;

    protected String SQL_GET_CYC_ID = """
            select cyc_id
            from cycle
            order by cyc_start desc
            limit 1
            """
    ;

    protected String SQL_GET_HISTORY = """
             with base as (
                 select
                     cyc_id
                      , date(cyc_start) as start_cycle
                      , lag(cyc_start) over (order by date(cyc_start)) as last_cycle
                 from cycle
                 order by date(cyc_start)
             ), length as (
                 select cyc_id
                      , start_cycle
                      , last_cycle
                      , julianday(start_cycle) - julianday(last_cycle) as cycle_length
                 from base
                 where last_cycle is not null
             ), bleeding_days as (
                 select cyc_id, count(*) as first_interval
                 from c_bleeding
                 group by cyc_id
             ), interval as (
                 select length.cyc_id
                      , start_cycle
                      , cycle_length
                      , first_interval
                      , round(cycle_length/2 - first_interval -4) as second_interval
                      , cycle_length - first_interval - round(cycle_length/2 - first_interval -4) -7 as fourth_interval
                 from length
                          inner join bleeding_days
                                     on bleeding_days.cyc_id = length.cyc_id
                 group by length.cyc_id
             )
             select *
             from interval
            """
    ;

    /**
     * constructor
     */
    public Db() {
    }

    /*
     * set Up password
     */
    public boolean setUp(String password){
        this.util = new Util();
        return this.util.setUp(password);
    }

    /*
     * adds new cycle to Db
     */
    public int insertCycle(LocalDate date) {
        this.util.update("insert into cycle (cyc_start) values('" + date + "')");
        return 1;
    }

    public int closeCycle(){
        ResultSet rs = getLastBleedingLength();
        int countBleedingDay = 1, cycID = 0;
        try {
            while (rs.next()) {
                countBleedingDay = rs.getInt("count_bleeding_days");
                cycID = rs.getInt("cyc_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        if(countBleedingDay == 0) {
            insertBleeding(1, cycID);
        }
        return 1;
    }

    public ResultSet getLastBleedingLength(){
        return this.util.query("""
                select cycle.cyc_id, cyc_start, count(c_bleeding.cyc_id) as count_bleeding_days
                from cycle
                    left join c_bleeding
                        on cycle.cyc_id = c_bleeding.cyc_id
                group by cycle.cyc_id, cyc_start
                order by cycle.cyc_id desc
                limit 1 offset 1""");
    }

    /**
     * Add new Mood Input to Db
     */
    public int insertMood(int value) {
        this.util.update("insert into c_mood (cyc_id, mood) values((" + SQL_GET_CYC_ID + "),'" + value + "')");
        return 1;
    }

    /**
     * Add new Flow Input to Db
     */
    public int insertOutflow(int value) {
        this.util.update("insert into c_outflow (cyc_id, outflow) values((" + SQL_GET_CYC_ID + "),'" + value + "')");
        return 1;
    }

    /**
     * Add new Temperature Input to Db
     */
    public int insertTemperature(String value) {
        this.util.update("insert into c_temperature (cyc_id, temperature_value) values((" + SQL_GET_CYC_ID + "),'" + value + "')");
        return 1;
    }

    /**
     * Add new Comment Input to Db
     */
    public int insertComment(String comment) {
        this.util.update("insert into c_comment (cyc_id, comment) values((" + SQL_GET_CYC_ID + "),'" + comment + "')");
        return 1;
    }

    /*
     * Add new Bleeding Cycle Input to Db
     */
    public int insertBleeding(int value, int cycId) {
        this.util.update("insert into c_bleeding (cyc_id, bleeding) values((" + cycId + "),'" + value + "')");
        return 1;
    }

    /*
     * Add new Ovulation Input to Db
     */
    public int insertOvulation(LocalDate date) {
        this.util.update("insert into c_ovulation (cyc_id, ovulation_date) values((" + SQL_GET_CYC_ID + "),'" + date + "')");
        return 1;
    }

    /*
     * erases all data from Db
     */
    public void deleteCycle() {
        this.util.update("delete from cycle");
        this.util.update("delete from c_bleeding");
        this.util.update("delete from c_comment");
        this.util.update("delete from c_mood");
        this.util.update("delete from c_outflow");
        this.util.update("delete from c_ovulation");
        this.util.update("delete from c_temperature");
    }

    /*
     * History Cycles:
     */
    public ResultSet getCyclesHistoryIntervals() {
        return this.util.query(
                this.SQL_GET_HISTORY);
    }

    /*
     * returns the last Cycle Date
     */
    public ResultSet getCountHistoryCycles() {
        return this.util.query("""
                select count(*) as counter
                from(""" + this.SQL_GET_HISTORY + ")"
        );
    }

    /*
     * returns the Temperature from given Day
     */
    public ResultSet getTemperature() {
        return this.util.query("""
            select temperature_value
            from c_temperature
            where cyc_id = (
                select cyc_id
                from cycle
                order by cyc_start desc
                limit 1
            )"""
        );
    }

    /*
     * Prediction Daten:
     * - Start Datum (aktueller Zyklus)
     * - Voraussichtliches Enddatum
     * - durchschnittliche Blutungstage
     * - Durchschnittliche Zyklusdaurer der letzten 12 Monate
     * - längste und kürzerste Zyklus dauer
     * - Fruchtbarkeitsdauer
     */
    public ResultSet getPredictionCycle() {
        return this.util.query("""
                with
                    bleeding_in_cycle as (
                        select cyc_id
                             , count(*) as bleeding_days
                        from c_bleeding
                        group by cyc_id
                    )
                   , cycle_reference as (
                    select cycle.cyc_id
                         , date(cycle.cyc_start) as start_cycle
                         , lag(cycle.cyc_start) over (order by date(cycle.cyc_start)) as prev_start_cycle
                         , cb.bleeding_days
                         -- , lead(cyc_start) over (order by date(cyc_start)) as next_cycle
                    from cycle
                             inner join bleeding_in_cycle cb
                                        on cycle.cyc_id = cb.cyc_id
                    order by date(cyc_start) desc
                )
                   , length as (
                    select base.cyc_id
                         , base.start_cycle
                         -- , date(base.prev_start_cycle, '-1 day') as end_cycle
                         , base.prev_start_cycle
                         , julianday(base.start_cycle) - julianday(base.prev_start_cycle) as last_cycle_length
                         , base.bleeding_days
                    from cycle_reference as base
                    where prev_start_cycle is not null
                )
                -- select * from length;
                   , min_max_in_12_months as (
                    select cyc_id
                         , start_cycle
                         , last_cycle_length
                         , round(avg(bleeding_days) over (order by date(start_cycle) rows between 11 preceding and current row),0) as avg_bleeding_days_in_last_12_months
                         , round(avg(last_cycle_length) over (order by date(start_cycle) rows between 11 preceding and current row),0) as avg_cycle_length_in_last_12_months
                         , min(last_cycle_length) over (order by date(start_cycle) rows between 11 preceding and current row) as min_cycle_length_in_last_12_months
                         , max(last_cycle_length) over (order by date(start_cycle) rows between 11 preceding and current row) as max_cycle_length_in_last_12_months
                         , count(*) over (order by date(start_cycle) rows between 11 preceding and current row) as available_measures
                    from length
                    group by cyc_id, last_cycle_length
                )
                select
                    cyc_id
                     , start_cycle
                     , avg_bleeding_days_in_last_12_months
                     , avg_cycle_length_in_last_12_months
                     , min_cycle_length_in_last_12_months
                     , max_cycle_length_in_last_12_months
                     -- fertilty_start:
                     , min_cycle_length_in_last_12_months - 18  - avg_bleeding_days_in_last_12_months as second_interval
                     , avg_cycle_length_in_last_12_months - avg_bleeding_days_in_last_12_months - (min_cycle_length_in_last_12_months - 18  - avg_bleeding_days_in_last_12_months) - ( (max_cycle_length_in_last_12_months - 11) - (min_cycle_length_in_last_12_months - 18))as fourth_interval
                     , (max_cycle_length_in_last_12_months - 11) - (min_cycle_length_in_last_12_months - 18) as fertility_length
                     , date(start_cycle, '+' || avg_cycle_length_in_last_12_months || ' days') as end_cycle
                     , date(start_cycle, '+' ||(min_cycle_length_in_last_12_months - 18)||  ' day') as fertility_start
                     , date(start_cycle, '+' ||(max_cycle_length_in_last_12_months - 11) ||  ' day') as fertility_end
                     , available_measures
                from min_max_in_12_months
                order by start_cycle desc
                limit 1"""
        );
    }

    /*
     * returns average cycle length
     */
    public ResultSet getAvg() {
        return this.util.query(this.SQL_STATS + """
            select cycle_avg_days
            from cycle_avg
            """
        );
    }

    public ResultSet getOutflow() {
        return this.util.query("""
            select outflow
            from c_outflow
            where cyc_id = (
                select cyc_id
                from cycle
                order by cyc_start desc
                limit 1)"""
        );
    }
}




