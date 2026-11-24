#include <Arduino.h>

const byte TRIG[] = {2, 3, 4, 5, 6};
const byte ECHO[] = {7, 8, 9, 10, 11};
const byte HOLES = sizeof(TRIG) / sizeof(TRIG[0]);
const byte PASSIVE_BUZZER = 13;
const byte ACTIVE_BUZZER = A0;
const byte LED_R = A1, LED_G = A2, LED_B = A3;
const byte START_BUTTON = A4;
const bool LED_ACTIVE_LOW = false;
const bool BUZZER_ACTIVE_LOW = false;

const unsigned long COUNTDOWN_MS = 5000;
const unsigned long GAME_MS = 60000;
// Ping one sensor at a time. Each hole gets a sample about every 100 ms.
const unsigned long PING_GAP_MS = 20;
const unsigned long ECHO_TIMEOUT_US = 3500;
const byte HIT_CM[HOLES] = {12, 12, 12, 12, 12};
const byte CLEAR_CM[HOLES] = {18, 18, 18, 18, 18};
// Enable temporarily for distance calibration. Close Java and use Serial Monitor.
const bool DIAGNOSTICS = false;

const byte START = 0x01, FINISH = 0x02, SCORE_BASE = 0x11;
const byte COUNT_BASE = 0x20, CLOCK_BASE = 0x40;
enum Phase { WAITING, COUNTDOWN, PLAYING, FINISHED };
Phase phase = WAITING;
unsigned long phaseAt, rawChangedAt, lastPingAt, flashAt, beepAt;
bool activeBeep = false, flashing = false;
bool rawButton, stableButton;
bool armed[HOLES];
byte clearSamples[HOLES];
byte nextSensor, lastSecond;

struct Note { unsigned int hz, ms; };


const Note READY_TUNE[] = {
{392, 130}, {523, 130}, {659, 130}, {784, 260}
};

const Note START_TUNE[] = {
{523, 95}, {659, 95}, {784, 95}, {1047, 300}
};

const Note FINISH_TUNE[] = {
{784, 150}, {659, 150}, {523, 150}, {659, 150}, {1047, 430}
};

const Note BACKGROUND_TUNE[] = {
{392, 180}, {494, 180}, {587, 180}, {494, 180},
{440, 180}, {523, 180}, {659, 180}, {523, 180},
{494, 180}, {587, 180}, {740, 180}, {587, 180},
{523, 180}, {494, 180}, {440, 180}, {392, 360}
};





const Note* melody = nullptr;
byte melodyLength, noteIndex;
unsigned long noteAt;
unsigned int noteDuration;

void sendByte(byte value) {
  if (!DIAGNOSTICS) Serial.write(value);
}

void rgb(bool red, bool green, bool blue) {
  digitalWrite(LED_R, red != LED_ACTIVE_LOW);
  digitalWrite(LED_G, green != LED_ACTIVE_LOW);
  digitalWrite(LED_B, blue != LED_ACTIVE_LOW);
}

void playMelody(const Note* notes, byte count, unsigned long now) {
  noTone(PASSIVE_BUZZER);
  melody = notes;
  melodyLength = count;
  noteIndex = 0;
  noteAt = now;
  noteDuration = 0;
}

void updateMelody(unsigned long now) {
  if (!melody || now - noteAt < noteDuration) return;
  if (noteIndex == melodyLength) {
    melody = nullptr;
    return;
  }
  const Note& note = melody[noteIndex++];
  tone(PASSIVE_BUZZER, note.hz, note.ms - 25);
  noteAt = now;
  noteDuration = note.ms;
}

void startCountdown(unsigned long now) {
  phase = COUNTDOWN;
  phaseAt = now;
  lastSecond = 0;
  rgb(true, true, false);
  playMelody(READY_TUNE, sizeof(READY_TUNE) / sizeof(Note), now);
}

void startGame(unsigned long now) {
  phase = PLAYING;
  phaseAt = now;
  nextSensor = 0;
  lastPingAt = now - PING_GAP_MS;
  lastSecond = 60;
  for (byte i = 0; i < HOLES; i++) {
    armed[i] = true;
    clearSamples[i] = 0;
  }
  sendByte(START);
  sendByte(CLOCK_BASE + 60);
  rgb(false, true, false);
  playMelody(START_TUNE, sizeof(START_TUNE) / sizeof(Note), now);
}

void finishGame(unsigned long now) {
  phase = FINISHED;
  flashing = false;
  activeBeep = false;
  digitalWrite(ACTIVE_BUZZER, BUZZER_ACTIVE_LOW);
  sendByte(FINISH);
  rgb(false, false, true);
  playMelody(FINISH_TUNE, sizeof(FINISH_TUNE) / sizeof(Note), now);
}

void pollButton(unsigned long now) {
  bool pressed = digitalRead(START_BUTTON) == LOW;
  if (pressed != rawButton) {
    rawButton = pressed;
    rawChangedAt = now;
  }
  if (pressed != stableButton && now - rawChangedAt >= 40) {
    stableButton = pressed;
    if (pressed && (phase == WAITING || phase == FINISHED)) startCountdown(now);
  }
}

int readDistance(byte hole) {
  digitalWrite(TRIG[hole], LOW);
  delayMicroseconds(3);
  digitalWrite(TRIG[hole], HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG[hole], LOW);
  unsigned long duration = pulseIn(ECHO[hole], HIGH, ECHO_TIMEOUT_US);
  return duration ? int(duration / 58UL) : -1;
}

void scoreHole(byte hole, unsigned long now) {
  const byte colors[] = {4, 2, 1, 6, 5};
  byte color = colors[hole];
  sendByte(SCORE_BASE + hole);
  rgb(color & 4, color & 2, color & 1);
  flashing = true;
  flashAt = now;
  digitalWrite(ACTIVE_BUZZER, !BUZZER_ACTIVE_LOW);
  activeBeep = true;
  beepAt = now;
}

void pollSensor(unsigned long now) {
  if (now - lastPingAt < PING_GAP_MS) return;
  lastPingAt = now;
  byte hole = nextSensor;
  nextSensor = (nextSensor + 1) % HOLES;
  int distance = readDistance(hole);
  unsigned long sampledAt = millis();
  if (sampledAt - phaseAt >= GAME_MS) return;

  if (DIAGNOSTICS) {
    Serial.print(F("Hole "));
    Serial.print(hole + 1);
    Serial.print(F(" cm: "));
    Serial.println(distance);
  }

  // A distance frame has a hole header and a separately ranged payload.
  sendByte(0x81 + hole);
  sendByte(0xA0 + (distance < 0 ? 63 : (distance > 62 ? 62 : distance)));

  if (distance >= 2 && distance <= HIT_CM[hole]) {
    clearSamples[hole] = 0;
    if (armed[hole]) {
      armed[hole] = false;
      scoreHole(hole, sampledAt);
    }
  } else if (distance < 0 || distance >= CLEAR_CM[hole]) {
    if (clearSamples[hole] < 2) clearSamples[hole]++;
    if (clearSamples[hole] == 2) armed[hole] = true;
  } else {
    clearSamples[hole] = 0;
  }
}

void setup() {
  for (byte i = 0; i < HOLES; i++) {
    pinMode(TRIG[i], OUTPUT);
    digitalWrite(TRIG[i], LOW);
    pinMode(ECHO[i], INPUT);
  }
  pinMode(START_BUTTON, INPUT_PULLUP);
  rawButton = stableButton = digitalRead(START_BUTTON) == LOW;
  pinMode(PASSIVE_BUZZER, OUTPUT);
  pinMode(ACTIVE_BUZZER, OUTPUT);
  digitalWrite(ACTIVE_BUZZER, BUZZER_ACTIVE_LOW);
  pinMode(LED_R, OUTPUT);
  pinMode(LED_G, OUTPUT);
  pinMode(LED_B, OUTPUT);
  rgb(false, false, true);
  Serial.begin(9600);
}

void loop() {
unsigned long now = millis();
pollButton(now);
updateMelody(now);

if (activeBeep && now - beepAt >= 100) {
activeBeep = false;
digitalWrite(ACTIVE_BUZZER, BUZZER_ACTIVE_LOW);
}

if (flashing && now - flashAt >= 350) {
flashing = false;
rgb(false, true, false);
}

unsigned long elapsed = now - phaseAt;

if (phase == COUNTDOWN) {
if (elapsed >= COUNTDOWN_MS) {
startGame(now);
} else {
byte remaining = 5 - elapsed / 1000UL;
if (remaining != lastSecond) {
lastSecond = remaining;
sendByte(COUNT_BASE + remaining);
}
}
} else if (phase == PLAYING) {
if (elapsed >= GAME_MS) {
finishGame(now);
} else {
byte remaining = (GAME_MS - elapsed + 999UL) / 1000UL;
if (remaining != lastSecond) {
lastSecond = remaining;
sendByte(CLOCK_BASE + remaining);
}

if (melody == nullptr) {
playMelody(
BACKGROUND_TUNE,
sizeof(BACKGROUND_TUNE) / sizeof(BACKGROUND_TUNE[0]),
now
);
}

pollSensor(now);
}
}
}

void testLights() {
rgb(true, false, false); // Red on A1
delay(1000);

rgb(false, true, false); // Green on A2
delay(1000);

rgb(false, false, true); // Blue on A3
}


