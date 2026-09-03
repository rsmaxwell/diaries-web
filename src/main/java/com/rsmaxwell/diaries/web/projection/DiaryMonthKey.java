package com.rsmaxwell.diaries.web.projection;

import java.time.YearMonth;

public record DiaryMonthKey(long diaryId, YearMonth month) {
}
