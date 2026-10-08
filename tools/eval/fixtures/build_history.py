"""Generates history.json: an INVENTED, clearly fake user ("Tester Zed", synthetic) in the shape of the app's
BackupData export, so the same file feeds the memory exam and the honesty checks. Nothing here is a real person.
Run: python3 tools/eval/fixtures/build_history.py   (output is committed; the exam's gold answers depend on it)."""
import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

Z = ZoneInfo("Asia/Karachi")


def ms(day, hour=20, minute=0):
    y, m, d = map(int, day.split("-"))
    return int(datetime(y, m, d, hour, minute, tzinfo=Z).timestamp() * 1000)


# id, date, title, summary, mode, [user messages], [assistant replies are omitted: the exam only needs his words]
SESSIONS = [
 (1, "2026-08-03", "Getting started",
  "Tester works as a junior surveyor at Brindle Surveying in Quillhaven. Sister Mira calls on Sundays. He wants a GIS certification.",
  [ "i'm a junior surveyor at brindle surveying, in quillhaven. been there two years",
    "my sister mira calls me every sunday, that's the one thing i never miss",
    "i want to finish a gis certification this year but i keep not starting" ]),
 (2, "2026-08-06", "Late work",
  "Mr. Orlo, his manager at Brindle, keeps handing him work at 5pm. He loses most evenings and feels resentful but says nothing.",
  [ "mr. orlo dumps work on me at 5pm again, i was out of there at 9",
    "i didn't say anything. i never do. i just get quiet and resentful",
    "i'd rather be bad at something than invisible, that's what it feels like there" ]),
 (3, "2026-08-10", "Gym plan",
  "He agreed to go to the gym on Tuesdays and Thursdays at 6pm. He drinks about three energy drinks a day.",
  [ "i drink like three energy drinks a day to get through the afternoon",
    "ok gym tuesdays and thursdays at 6pm. i'll go this week, promise" ]),
 (4, "2026-08-17", "Skipped twice",
  "He skipped the gym twice and blamed late work, though one of those evenings he was free. Family matters to him but work eats the evenings.",
  [ "skipped gym both days. orlo again",
    "actually thursday i was home by 5:30. i just didn't go",
    "family is the most important thing to me, i keep saying it. and then i'm at work till 9" ]),
 (5, "2026-08-24", "Looking elsewhere",
  "He decided to look for another surveying job and promised to send two applications by Sunday.",
  [ "i think i should leave brindle. i'll send two applications by sunday" ]),
 (6, "2026-08-31", "Only one",
  "He sent one application, not two, and felt guilty. He started strong and dropped after the first miss, a pattern he recognised himself.",
  [ "i sent one. the second one i kept rewriting",
    "i always start strong and then one missed day and i'm done" ]),
 (7, "2026-09-07", "Interview prep",
  "Interview coming up at Halcyon Maps. He was nervous, practised answering why he wants to leave Brindle without bad-mouthing anyone.",
  [ "i got an interview at halcyon maps on thursday",
    "i don't want to trash orlo in the interview, honesty matters to me but so does being fair" ]),
 (8, "2026-09-13", "The offer",
  "Halcyon Maps offered him the job. He accepted. Start date October 1.",
  [ "they offered me the job at halcyon maps. i said yes. start is october 1",
    "i'm scared i'll be bad at it" ]),
 (9, "2026-09-17", "Handing in notice",
  "He handed in his notice at Brindle Surveying and Mr. Orlo took it quietly. He said it was the first time he spoke up there.",
  [ "i gave notice today. orlo just nodded, i said what i needed to say calmly" ]),
 (10, "2026-09-21", "Moving to Dunmere",
  "He will move from Quillhaven to Dunmere for the new job. Worried about telling Mira he is scared. Promised to call Mira every Sunday.",
  [ "i'm moving from quillhaven to dunmere before the start date",
    "mira is planning her wedding and i haven't told her i'm scared about moving",
    "i'll call mira every sunday no matter what, even on the move" ]),
 (11, "2026-09-26", "No more energy drinks",
  "He stopped energy drinks five days ago and feels clearer. He also moved the gym plan to Monday, Wednesday and Friday at 7am because the new job ends later.",
  [ "no energy drinks for five days now, my head is clearer",
    "gym is changing, mon wed fri at 7am now. evenings won't work at halcyon" ]),
 (12, "2026-09-29", "One slip",
  "He had one energy drink and shrugged it off, then said it matters more than he admits. He is not drinking daily again.",
  [ "i had one energy drink today. it's fine, one is fine",
    "no. it's not fine, i keep saying it's fine and it's how it starts" ]),
 (13, "2026-10-01", "First day",
  "First day at Halcyon Maps in Dunmere. He moved into a new flat. He promised to finish GIS module 3 by October 11.",
  [ "first day at halcyon done. it went ok. dunmere is quiet",
    "i'll finish gis module 3 by october 11" ]),
 (14, "2026-10-03", "Avoidance loop day 1",
  "Journey day 1 of Break the avoidance loop: he noticed avoidance right before opening his phone to skip a study block.",
  [ "did it. right before i opened instagram i felt bored and a bit anxious about the module" ]),
]

QUOTES = [  # (session, text) verbatim from the user messages above
 (2, "i'd rather be bad at something than invisible"),
 (4, "family is the most important thing to me"),
 (6, "i always start strong and then one missed day and i'm done"),
 (7, "honesty matters to me but so does being fair"),
 (12, "it's not fine, i keep saying it's fine and it's how it starts"),
]

EVENTS = [  # session, date, whenText, situation, feeling, action, payoff, outcome
 (2, "2026-08-06", "5pm", "manager handed over late work", "resentful", "said nothing", None, "left at 9pm"),
 (4, "2026-08-15", "Thursday evening", "free at home by 5:30", None, "skipped the gym anyway", None, "blamed work"),
 (6, "2026-08-29", "Sunday", "second application", "stuck", "kept rewriting it", None, "never sent"),
 (9, "2026-09-17", "morning", "handed in notice", "calm", "said what he needed to say", None, "first time he spoke up at Brindle"),
 (12, "2026-09-29", "afternoon", "had an energy drink", None, "told himself one is fine", "short boost", "later admitted it matters"),
 (14, "2026-10-03", "evening", "about to open instagram instead of studying", "bored and anxious", "noticed it and wrote it down", None, "did the step"),
]

PROFILE = [  # key, value, date, sessions, retired
 ("employer", "Junior surveyor at Halcyon Maps in Dunmere (started 1 October 2026).", "2026-10-01", "13", False),
 ("city", "Lives in Dunmere (moved 1 October 2026).", "2026-10-01", "13", False),
 ("values_top", "Top values, in order: honesty, family, craft, health, freedom.", "2026-08-17", "4,7", False),
 ("goal_certification", "Wants a GIS certification this year; on module 3 of the course.", "2026-10-01", "1,13", False),
 ("sister", "Sister Mira calls him on Sundays; she is planning her wedding.", "2026-09-21", "1,10", False),
 ("employer_old", "Junior surveyor at Brindle Surveying in Quillhaven under Mr. Orlo.", "2026-08-03", "1,2", True),
 ("city_old", "Lives in Quillhaven.", "2026-08-03", "1", True),
]

# id, type, text, confidence, status, timesSeen, firstSeen, lastSeen, sessions
NOTES = [
 (1, "pattern", "You start strong and drop everything after one missed day.", "likely", "active", 3, "2026-08-31", "2026-09-29", "6,11,12"),
 (2, "pattern", "You blame work for skipped plans even on evenings you were free.", "guess", "active", 1, "2026-08-17", "2026-08-17", "4"),
 (3, "what_helps", "Saying the plan out loud with a specific time.", "likely", "active", 2, "2026-08-10", "2026-09-26", "3,11"),
 (4, "what_doesnt", "Vague promises like 'sometime this week'.", "guess", "active", 1, "2026-08-24", "2026-08-24", "5"),
 (5, "thread", "Whether to tell Mira you are scared about the move.", "guess", "active", 1, "2026-09-21", "2026-09-21", "10"),
 (6, "thread", "You told yourself one energy drink is fine; you said it matters more than that.", "guess", "active", 1, "2026-09-29", "2026-09-29", "12"),
 (7, "thread", "Mr. Orlo kept handing you late work and you stayed quiet.", "likely", "retired", 2, "2026-08-06", "2026-09-17", "2,9"),
 (8, "what_helps", "Gym on Tuesdays and Thursdays at 6pm.", "guess", "retired", 1, "2026-08-10", "2026-09-26", "3"),
]

# id, text, why, created, due, status, session, resolved, whatHappened, lesson
PROMISES = [
 (1, "Gym on Tuesdays and Thursdays at 6pm", "health", "2026-08-10", "2026-08-14", "broken", 3, "2026-08-17", "Skipped both days.", "The plan fell apart on the first miss."),
 (2, "Send two job applications by Sunday", "leave Brindle", "2026-08-24", "2026-08-30", "broken", 5, "2026-08-31", "Sent one, not two.", "He rewrote the second one instead of sending it."),
 (3, "Call Mira every Sunday", "family", "2026-09-21", None, "open", 10, None, None, None),
 (4, "Gym Monday, Wednesday and Friday at 7am", "health", "2026-09-26", None, "open", 11, None, None, None),
 (5, "Finish GIS module 3 by October 11", "certification", "2026-10-01", "2026-10-11", "open", 13, None, None, None),
 (6, "Walk through the interview answers once more", "interview", "2026-09-07", "2026-09-10", "kept", 7, "2026-09-13", "He practised and the interview went well.", "Rehearsing out loud helps."),
]

JOURNEYS = [("Break the avoidance loop", 7, 2, "active", "2026-10-03", "2026-10-03")]


def build():
    sessions, messages, mid = [], [], 1
    for sid, day, title, summary, users in SESSIONS:
        t0 = ms(day, 20, 0)
        mode, detail, jday = (("journey", "Break the avoidance loop", 1) if sid == 14 else (None, None, None))
        sessions.append({"id": sid, "startedAt": t0, "endedAt": t0 + 15 * 60000, "summary": summary, "reflected": True,
                         "userMessageCount": len(users), "mode": mode, "modeDetail": detail, "journeyDay": jday, "title": title})
        for i, u in enumerate(users):
            messages.append({"id": mid, "sessionId": sid, "role": "user", "content": u, "createdAt": t0 + (2 * i + 1) * 60000,
                             "status": "complete"})
            mid += 1
            messages.append({"id": mid, "sessionId": sid, "role": "assistant", "content": "(coach reply omitted in fixture)",
                             "createdAt": t0 + (2 * i + 2) * 60000, "status": "complete"})
            mid += 1
    when = {s[0]: s[1] for s in SESSIONS}
    return {
        "synthetic": True,
        "note": "INVENTED test user. Every name, place and fact here is fake. Used only by the Purpose eval harness.",
        "name": "Tester Zed",
        "today": "2026-10-04",
        "formatVersion": 3, "schemaVersion": 0, "exportedAt": ms("2026-10-04", 12, 0),
        "sessions": sessions, "messages": messages,
        "profile": [{"key": k, "value": v, "updatedAt": ms(d), "editedByUser": False, "deletedByUser": False,
                     "sourceSessionIds": s, "retired": r} for k, v, d, s, r in PROFILE],
        "people": [{"id": 1, "name": "Mira", "relation": "sister", "notes": "Planning her wedding; calls on Sundays.", "updatedAt": ms("2026-09-21")},
                   {"id": 2, "name": "Mr. Orlo", "relation": "former manager at Brindle Surveying", "notes": "Handed him late work.", "updatedAt": ms("2026-09-17")}],
        "notes": [{"id": i, "type": t, "text": x, "confidence": c, "status": st, "timesSeen": n, "firstSeen": ms(f), "lastSeen": ms(l),
                   "editedByUser": False, "sourceSessionIds": s} for i, t, x, c, st, n, f, l, s in NOTES],
        "promises": [{"id": i, "text": t, "why": w, "createdAt": ms(c), "dueAt": d, "status": st, "sourceSessionId": s,
                      "resolvedAt": ms(r, 20, 10) if r else None, "whatHappened": wh, "lesson": le}
                     for i, t, w, c, d, st, s, r, wh, le in PROMISES],
        "quotes": [{"id": i + 1, "sessionId": s, "text": t, "createdAt": ms(when[s])} for i, (s, t) in enumerate(QUOTES)],
        "behaviorEvents": [{"id": i + 1, "sessionId": s, "createdAt": ms(d), "whenText": w, "situation": sit, "feelingBefore": f,
                            "action": a, "payoff": p, "outcome": o, "deletedByUser": False}
                           for i, (s, d, w, sit, f, a, p, o) in enumerate(EVENTS)],
        "strengths": [{"id": 1, "sessionId": 9, "text": "You said what you needed to say, calmly, when you handed in your notice.", "createdAt": ms("2026-09-17"),
                       "deletedByUser": False, "retired": False}],
        "journeys": [{"id": 1, "name": n, "startedAt": ms(st), "currentDay": cd, "totalDays": td, "status": s, "lastStepDate": ls}
                     for n, td, cd, s, st, ls in JOURNEYS],
        "journeyAdjustments": [], "letters": [], "chapters": [],
    }


if __name__ == "__main__":
    out = Path(__file__).with_name("history.json")
    out.write_text(json.dumps(build(), indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print("wrote", out)
