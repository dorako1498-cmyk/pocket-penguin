# v0.7 - Jinbei the whale-shark buddy

New second character "Jinbei" (ジンベエ), a chubby deformed whale-shark plush (original art, same ink outline as the penguin).
- Art: tools/make_buddy_assets.py -> bd_body / bd_fin / bd_tail (+ BuddyLayout.java). Face, blush, mouth are drawn at runtime.
- Buddy.java: own state machine (18 states: SNOOZE, STIR, YAWN, IDLE, LOOK, CRAWL, SWIM, WIGGLE, ROLL, BUBBLES, SURPRISE, PETTED, TICKLE, SHY, CALL, CHASE_TAIL, DROWSY, JOY_HOP), springs, squash & stretch, nose bubble while sleeping.
- Sleepy by nature: dozes most of the night, naps between play sessions; rain makes it swim and blow bubbles.
- With the penguin: visits and greets, bumps (penguin hops), naps beside a sleeping penguin, calls the penguin and gives it a ride on its back (Seqs.RIDE, Brain.startRide).
- Touch: tap to wake, head = wiggle / repeated taps = stroke, belly = tickle, tail = chases it, 3 taps = barrel roll, 4+ = surprise; swipe strokes it; eyes follow the finger.
- Settings: "Jinbei" switch (pref `buddy`). Fx.BUBBLE particle added.
