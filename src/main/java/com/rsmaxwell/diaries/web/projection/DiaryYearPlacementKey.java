package com.rsmaxwell.diaries.web.projection;

import com.rsmaxwell.diaries.web.model.FragmentPlacement;

record DiaryYearPlacementKey(long diaryId, int year, FragmentPlacement placement) {}
