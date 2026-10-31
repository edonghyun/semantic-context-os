package io.scos.engine;

/** 실제로 적용된 검색 계획. escalated는 강도를 올려 다시 찾았는지다. */
public record AppliedPlan(Effort effort, Profile profile, boolean escalated) {}
