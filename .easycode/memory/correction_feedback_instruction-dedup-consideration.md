---
type: correction_feedback
title: 指令加载系统应考虑去重
created: 2026-06-21T00:47:03.834919682+08:00
updated: 2026-06-21T00:47:03.834919682+08:00
---

用户反馈：三层加载（项目根、.easycode/、~/.easycode/）仅用 StringBuilder 拼接，若 LLM 判断边界不清可能在多层中记录相同语句，应考虑增加去重逻辑。
