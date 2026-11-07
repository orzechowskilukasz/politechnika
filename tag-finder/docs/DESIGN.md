# Finding a Find My tag with an Android phone: the physics and the design

This document covers what an Android phone can measure about a nearby AirTag, which sensor
tricks really improve positioning, which ones don't, and why. The app is built from the
ideas that survived. Numbers marked **[sim]** come from the synthetic-room benchmark in
`core/src/test` (`./gradlew -PcoreOnly :core:simulate`). Numbers from the literature are
typical ranges, not measurements of your home.

---

## 1. What we can actually measure

| Source | What Android exposes | Notes |
|---|---|---|
| Bluetooth LE | Advertisement payload, **RSSI in whole dB**, timestamp | No phase, no carrier frequency, no channel number, no angle of arrival |
| UWB (some phones) | Ranging/AoA, but **only with a peer that agrees to a session** | AirTags only do UWB with their owner's Apple devices, so it's unusable here |
| Camera + IMU (ARCore) | 6-DoF phone pose (cm-level over a room), planes, depth | Visual-inertial odometry |
| Gyro / accelerometer | Orientation, steps | Heading drift a few °/min |
| Magnetometer | 3-axis field, µT | Distorted indoors; useful only at very short range |

So the only radio observable is **RSSI**. Everything else either says *where the phone was* when
it heard the tag, or helps with the last few centimetres.

### Ideas rejected on physics grounds

**Doppler.** At 2.4 GHz, walking at 1 m/s gives f·v/c ≈ 8 Hz. BLE crystals are allowed ±20–50 ppm
(±50–120 kHz), and a 0.4 ms advertisement only resolves ~2.5 kHz. Advertisements hop across
channels 37/38/39 without phase continuity. Android doesn't expose frequency at all.
*Not feasible.*

**Interferometry / angle of arrival.** This needs carrier phase from two or more coherent antennas.
Phones don't expose it. Bluetooth 5.1 direction finding needs the *transmitter* to send a
Constant Tone Extension, and Bluetooth 6.0 Channel Sounding needs both ends to support it.
AirTags do neither (they use UWB). *Not feasible.*

**Mapping the interference pattern.** Multipath creates a standing-wave pattern with
λ/2 ≈ 6 cm structure, and its spatial spectrum does contain arrival angles. But the tag
advertises only about every 0.5–2 s (the app measures and shows this rate), so sampling a
6 cm pattern at the Nyquist rate means moving the phone about 1–3 cm/s while keeping cm-level pose.
RSSI is also power-only (no sign) and quantised to 1 dB. *Not practical.* What we do
instead is the opposite: **average the interference away** (§3).

---

## 2. Signal model

For a reading taken with the phone at **p**, camera facing **f**, and the tag at **s**:

```
RSSI = P₀ − 10·n·log₁₀‖s − p‖              path loss (log-distance model)
       − B·(1 − cos∠(s − p, f))/2            body shadow: 0 in front of you, B behind you
       − L_w·(walls crossed)                 multi-wall model
       + X_shadow(p)                         shadowing: N(0, σ_s²), correlated over ~1–2 m
       + F(p, channel)                       multipath fading, decorrelates over ~λ/2 = 6 cm
       + quantisation (1 dB)
```

Typical indoor values: P₀ (RSSI at 1 m) around −55 to −65 dBm, n ≈ 1.6–3.5, σ_s ≈ 3–6 dB,
B ≈ 5–15 dB (the human torso is mostly water), L_w ≈ 3–5 dB for drywall and 8–15 dB for brick or concrete.

**Multipath fading** in a cluttered room is close to Rayleigh-distributed: received
*power* is exponential. In dB, one reading has σ = **5.57 dB** and its mean is biased
**2.5 dB low** (Euler–Mascheroni constant × 10/ln10). A single reading can therefore be off by
±10 dB just because of *where the phone is to within a few centimetres*.

**The fundamental limit.** Even with fading averaged out, shadowing remains. A range estimate from
the local mean RSSI has relative error

```
σ_d / d ≈ σ_s · ln10 / (10·n)  ≈  4 · 2.303 / 22  ≈  0.42      (σ_s = 4 dB, n = 2.2)
```

So at 3 m, one measurement is ±1.3 m. This is why "RSSI → distance" apps feel random, and why
we have to combine *many positions* rather than trust one reading.

---

## 3. Scribble scanning (spatial-diversity averaging)

Moving the phone in small circles about 30 cm across visits many λ/2 cells. Each cell has an
independent fade but the *same* path loss and shadowing. Averaging N independent looks
**in linear power (mW)**, which is unbiased, rather than in dB, which is biased, gives the
*local mean*. The local mean is Gamma(N, 1/N)-distributed, so its error in dB is

```
σ_fade(N) = (10 / ln10) · √ψ₁(N)        (ψ₁ = trigamma; N = 1 → 5.57 dB, N = 10 → 1.4 dB, N = 25 → 0.88 dB)
```

`Rssi.fadingStdDb` implements this. `SiteAggregator` counts **distinct λ/2 cells visited**,
not raw readings, because repeated readings from one cell see the same fade.

**Measured effect [sim]:** scribbling clearly stabilises the *live hot/cold reading* at a
spot. For the *map* it only gives a small gain (mean error 2.69 → 2.56 m), because once you
have hundreds of readings, a least-squares fit already averages the fading, and the shadowing
that remains is metres-scale. So the app uses scribbling for the hot/cold meter and as a cheap
way to get many independent looks per spot. The heavy lifting is done by §4–§6.

---

## 4. Body-shadow direction finding (gyro)

Your body blocks the signal by B dB when it is between the phone and the tag. Hold the phone
to your chest and turn on the spot, and RSSI follows

```
r(θ) = a + b·cos(θ − φ) + ε      ⇔      r = a + c₁·cosθ + c₂·sinθ + ε     (linear least squares)
φ = atan2(c₂, c₁)        b = √(c₁² + c₂²)
σ_φ² = s² · gᵀ (XᵀX)⁻¹ g,  g = (0, −c₂/b², c₁/b²)                          (delta method)
```

`BearingEstimator` implements this and refuses to answer until at least 6 of 8 heading
sectors are covered. Heading comes from **GAME_ROTATION_VECTOR** (gyro + accelerometer),
which deliberately leaves out the magnetometer: indoor steel bends the compass by tens of degrees,
and we only need headings that are consistent with each other for about 30 s.

**[sim]** 1.5 turns, ~54 readings, full fading and shadowing: **median bearing error 10°**.

Folding the same body-shadow term into the 3-D localiser (§6) was **the single biggest
improvement [sim]**: mean error 2.56 → 1.90 m. It turns your body into a directional antenna.

---

## 5. What the camera adds

### 5a. Pose (essential)

Pedestrian dead reckoning (step counting + heading) drifts 2–5% of distance and can't see
the hand's centimetre-scale scribbles at all. ARCore's visual-inertial odometry gives the phone
position to roughly centimetres over a room, so every reading gets a 3-D position and a
camera direction. That makes the whole walk a **synthetic aperture**: one antenna,
many positions.

### 5b. Surfaces as a prior (helps)

Lost things rest *on* something: the floor, a table, a shelf, a sofa. ARCore plane detection
finds those surfaces. The localiser gives cells within 25 cm above a detected horizontal
surface (or the floor) normal prior weight, and cells in mid-air e⁻² of that. This collapses a
3-D search into roughly a 2-D one. **[sim]:** mean error 1.90 → 1.67 m.

### 5c. Room shape → predicting scattering (your question)

**Could the camera map the room and let us compute how the signal scatters?** In principle,
the image method / ray tracing gives the received field as a sum over reflected paths:

```
E(p) = Σᵢ Γᵢ · e^(−j·k·dᵢ) / dᵢ        k = 2π/λ,  dᵢ = length of path i,  Γᵢ = product of reflection coefficients
```

Predicting the **fading pattern** (the thing that makes RSSI jump) needs path lengths accurate
to about λ/10 ≈ **1 cm** for every reflector. It also needs the material of every surface (Γ), every
piece of furniture and every person in the room, and the exact advertising channel (a 3% frequency change
reshuffles all the phases). The channel isn't reported, and RSSI only has 1 dB resolution.
ARCore depth is accurate to roughly 1–5 cm, sees only what the camera saw, and has no idea
what a wall is made of. **Coherent multipath prediction from a phone camera is not achievable**,
which is why §3 averages the fading away instead.

What *does* survive from the geometry is the **large-scale** part:

- **Walls between you and the tag.** Every detected vertical plane crossed by the line from
  phone to candidate adds L_w dB of loss (multi-wall / COST-231 style model). This is
  implemented in `Localizer` and switches on automatically when ARCore finds walls.
- **Surfaces where things can lie** (§5b).
- *(Possible future step)* an **incoherent** image-method term (adding the *powers* of a few
  first-order wall reflections) to correct the local mean near walls and in corridors. It's
  cheap and physically sound, but the expected gain is a few dB in specific geometries, and
  it needs reliable wall planes.

So yes, the camera's room model is used, but as a prior and a wall-loss model, not for
predicting interference.

---

## 6. The estimator: Bayesian grid search

`Localizer` evaluates every 20 cm cell **s** of a 3-D grid covering the walked area plus 4 m.
For each cell the model in §2 is **linear in (P₀, n)**, so they are solved in closed form
(ridge-regularised by priors P₀ ~ N(−60, 12²), n ~ N(2.0, 0.6²), with n clamped to 1.4–4.5).
The residual gives a profile likelihood:

```
log p(s | data) = −½ · τ · Σᵢ wᵢ (yᵢ − P₀ − n·uᵢ)²  + log prior(s)
uᵢ = −10·log₁₀‖s − pᵢ‖,   yᵢ = mᵢ + body(s, pᵢ, fᵢ) + walls(s, pᵢ),   wᵢ = 1/(σ_s² + σ_fade(Nᵢ)²)
τ = (# independent 1 m cells) / (# sites)      shadowing is correlated, so don't over-count
```

Self-calibrating P₀ and n means the app needs no per-tag or per-room calibration. The result
is a posterior map. The app shows the peak of its top-down marginal (more stable than the
single best 3-D cell) and a 50% credible radius.

### Guided second pass

Range resolution improves sharply close to the tag (the log curve is steep near the source).
The app tells the user to walk toward the marker, stop about 1 m short and scribble again.

### Benchmark [sim]

Synthetic 6 × 5 m room, 60 random tag positions (half on a table, half on the floor),
σ_s = 4 dB shadowing with 1.5 m correlation, Rayleigh fading per λ/2 cell and channel, 9 dB body
shadow, one reading every 0.5 s, an L-shaped walk with a 6 s scribble every 0.6 m.
Horizontal error of the reported search point:

| Method | median | 80th pct | mean | tag inside 50% circle |
|---|---|---|---|---|
| Raw RSSI, path loss only | 1.90 m | 4.27 m | 2.51 m | 65% |
| + scribble averaging | 2.11 m | 4.14 m | 2.56 m | 53% |
| + body-shadow model | 1.39 m | 2.00 m | 1.55 m | 80% |
| + camera surface prior | 1.08 m | 1.96 m | 1.38 m | 82% |
| + guided second pass | **0.95 m** | **1.78 m** | **1.21 m** | 87% |

The simulator uses the same model family as the estimator, so treat these numbers as an
upper bound on real-world accuracy. Real rooms add people moving, phone-antenna patterns,
and metal furniture.

---

## 7. The last metre

At under about 1 m, RSSI hardly changes with small moves, so the app switches tools:

- **Hot/cold meter.** Kalman-filtered RSSI with a warmer/colder trend over 6 s. Scribble at each spot.
- **Magnetometer.** The AirTag's speaker contains a small magnet. A dipole field falls off as 1/r³,
  so it only stands out against Earth's 25–65 µT within roughly 5–10 cm, but that's the
  "which cushion is it in" range. Readings are rotated into a world frame so turning the
  phone doesn't look like a detection.
- **Sound** (not implemented). If the tag can be made to beep (Find My on any Apple device,
  or Android's built-in *unknown tracker → play sound* once the tag has been separated from
  its owner), sound gives far better direction than RSSI. Microphone loudness gradients, or
  the time difference between the phone's two microphones (~15 cm apart, so ±0.44 ms), could
  be added as a later mode.

---

## 8. Identifying *your* tag

A Find My accessory periodically rotates its BLE address and key for privacy, and nothing in
the packet links the old and new identities. `TargetTracker` follows a rotation with a
continuity rule: the old address goes silent for ≥ 8 s, exactly one never-seen-before
address of the same kind (separated / near-owner) appears within 90 s, with RSSI within 12 dB.
If it's ambiguous, it doesn't switch.

Practical tip: an AirTag near its owner's iPhone advertises a short "nearby" packet. Separated,
it advertises the full 25-byte payload. Searching with the iPhone out of range makes the tag
easier to pick out (the strongest "separated" entry).

---

## 9. Sources and further reading

- Rappaport, *Wireless Communications: Principles and Practice*: log-distance path loss,
  log-normal shadowing, Rayleigh/Rician fading, spatial decorrelation at λ/2.
- Gudmundson (1991), *Correlation model for shadow fading in mobile radio systems*:
  exponential shadowing correlation.
- COST 231 final report: multi-wall indoor propagation model.
- Heinrich, Stute, Kornhuber, Hollick (2021), *Who Can Find My Devices?*: Find My protocol,
  advertisement format, key rotation.
- OpenHaystack and AirGuard (SEEMOO, TU Darmstadt): public reverse engineering of Find My
  advertisements.
- Bluetooth Core 5.1 (direction finding, CTE) and 6.0 (Channel Sounding): why these don't apply here.
- Google ARCore developer documentation: motion tracking, plane detection, depth.
