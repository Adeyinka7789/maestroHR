package com.admtechhub.maestrohr.overtime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PublicHolidayRepository extends JpaRepository<PublicHoliday, UUID> {

    /** All holidays for the management page, upcoming first. */
    List<PublicHoliday> findAllByOrderByHolidayDateDesc();

    Optional<PublicHoliday> findByHolidayDate(LocalDate holidayDate);

    /** Active holiday dates within a period — feeds the overtime holiday classification. */
    List<PublicHoliday> findByActiveTrueAndHolidayDateBetween(LocalDate from, LocalDate to);
}
