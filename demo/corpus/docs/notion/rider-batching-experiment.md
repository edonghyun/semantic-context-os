---
updated: 2026-09-17T00:00:00Z
tags: [experiment, dispatch]
---
# Rider batching experiment (Seoul, 2026-08-25 ~ 09-14)

## Hypothesis

Letting one rider carry two orders from the same store when drop-offs are within 800 m reduces cost per delivery without hurting delivery time.

## Setup

A/B test on 20% of Seoul riders for three weeks. Control: one order per trip. Treatment: dispatch may batch two orders when the second order is ready within 4 minutes.

## Results

- Cost per delivery: -11.8% (95% CI -13.1% to -10.4%)
- Median delivery time: +2.1 minutes (24.3 → 26.4 min)
- Late deliveries (> 45 min): 3.9% → 4.4%
- Customer rating: no significant change

## Decision

Roll out to 30% of riders, but cap the batching radius at 600 m to keep the median increase under 2 minutes. Rollout ticket: [OTC-322](../jira/OTC-322).
