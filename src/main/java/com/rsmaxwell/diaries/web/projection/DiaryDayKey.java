package com.rsmaxwell.diaries.web.projection;

import java.time.LocalDate;

public record DiaryDayKey(long diaryId, LocalDate date) {
}
