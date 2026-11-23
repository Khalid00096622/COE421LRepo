#include <Arduino.h>

// Uno pins. A4 push button to GND (INPUT_PULLUP). No servo is used.
const byte TRIG[5] = {2, 3, 4, 5, 6};
const byte ECHO[5] = {7, 8, 9, 10, 11};
const byte PASSIVE_BUZZER = 13;
const byte ACTIVE_BUZZER = A0;
const byte LED_R = A1, LED_G = A2, LED_B = A3;
const byte START_BUTTON = A4;
const unsigned long GAME_MS = 60000UL;
const unsigned long COUNTDOWN_MS = 5000UL;
const unsigned long SENSOR_INTERVAL_MS = 65UL; // per ultrasonic ping, sequential
const unsigned long SCORE_FLASH_MS = 350UL;
const unsigned long SCORE_BEEP_MS = 100UL;
const unsigned long BUTTON_DEBOUNCE_MS = 40UL;
const unsigned long PULSE_TIMEOUT_US = 12000UL;
const int HIT_DISTANCE_CM = 12; // CALIBRATE for each scoring tunnel
const int CLEAR_DISTANCE_CM = 18;
const bool LED_DRIVER_ACTIVE_LOW = false; 

const byte EVT_STARTED = 0x01;
const byte EVT_ENDED = 0x02;
const byte EVT_SCORE_BASE = 0x11; 

enum Phase { WAITING, COUNTDOWN, PLAYING };
Phase phase = WAITING;
unsigned long phaseStartedAt = 0;
unsigned long buttonChangedAt = 0;
bool buttonLastRaw = HIGH;
bool buttonStable = HIGH;
unsigned long lastPingAt = 0;
unsigned long flashUntil = 0;
unsigned long activeBeepUntil = 0;
byte lastCountdownNumber = 0;
byte nextSensor = 0;
byte clearReadings[5] = {0, 0, 0, 0, 0};
bool armed[5] = {true, true, true, true, true};

void setRgb(bool red, bool green, bool blue) {
  digitalWrite(LED_R, (red ^ LED_DRIVER_ACTIVE_LOW) ? HIGH : LOW);
  digitalWrite(LED_G, (green ^ LED_DRIVER_ACTIVE_LOW) ? HIGH : LOW);
  digitalWrite(LED_B, (blue ^ LED_DRIVER_ACTIVE_LOW) ? HIGH : LOW);
}

void showScoreColor(byte hole) {
  switch (hole) {
    case 0: setRgb(true, false, false); break;   // 10 red
    case 1: setRgb(false, true, false); break;   // 20 green
    case 2: setRgb(false, false, true); break;   // 30 blue
    case 3: setRgb(true, true, false); break;    // 40 yellow
    case 4: setRgb(true, false, true); break;    // 50 magenta
  }
}

void resetSensors() {
  for (byte i = 0; i < 5; i++) { armed[i] = true; clearReadings[i] = 0; }
  nextSensor = 0;
  lastPingAt = millis() - SENSOR_INTERVAL_MS;
}

void beginCountdown() {
  phase = COUNTDOWN;
  tone(PASSIVE_BUZZER, 1600, 100);
  phaseStartedAt = millis();
  lastCountdownNumber = 0;
  setRgb(true, true, false);
}

void startGame() {
  resetSensors();
  phase = PLAYING;
  phaseStartedAt = millis();
  setRgb(false, true, false);
  Serial.write(EVT_STARTED); 
  tone(PASSIVE_BUZZER, 1200, 200);
}

void endGame() {
  phase = WAITING;
  Serial.write(EVT_ENDED); 
  digitalWrite(ACTIVE_BUZZER, LOW);
  activeBeepUntil = 0;
  setRgb(false, false, true);
  tone(PASSIVE_BUZZER, 500, 600);
}

// Active-low button, debounced.
void pollStartButton() {
  bool raw = digitalRead(START_BUTTON);
  unsigned long now = millis();
  if (raw != buttonLastRaw) {
    buttonLastRaw = raw;
    buttonChangedAt = now;
  }
  if (now - buttonChangedAt >= BUTTON_DEBOUNCE_MS && raw != buttonStable) {
    buttonStable = raw;
    if (buttonStable == LOW && phase == WAITING) {
      beginCountdown();
    }
  }
}

void scoreHole(byte hole) {
  Serial.write((byte)(EVT_SCORE_BASE + hole)); 
  showScoreColor(hole);
  flashUntil = millis() + SCORE_FLASH_MS;
  digitalWrite(ACTIVE_BUZZER, HIGH);
  activeBeepUntil = millis() + SCORE_BEEP_MS;
}

int readDistanceCm(byte index) {
  digitalWrite(TRIG[index], LOW);
  delayMicroseconds(3);
  digitalWrite(TRIG[index], HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG[index], LOW);
  unsigned long duration = pulseIn(ECHO[index], HIGH, PULSE_TIMEOUT_US);
  if (duration == 0) return -1; 
  return (int)(duration / 58UL);
}

void pollOneSensor() {
  if (millis() - lastPingAt < SENSOR_INTERVAL_MS) return;
  lastPingAt = millis();
  byte i = nextSensor;
  nextSensor = (nextSensor + 1) % 5;
  int distance = readDistanceCm(i);
  if (distance >= 2 && distance <= HIT_DISTANCE_CM) {
    clearReadings[i] = 0;
    if (armed[i]) { armed[i] = false; scoreHole(i); }
  } else if (distance >= CLEAR_DISTANCE_CM || distance < 0) {
    if (clearReadings[i] < 2) clearReadings[i]++;
    if (clearReadings[i] >= 2) armed[i] = true;
  }
}

void setup() {
  for (byte i = 0; i < 5; i++) {
    pinMode(TRIG[i], OUTPUT);
    pinMode(ECHO[i], INPUT);
    digitalWrite(TRIG[i], LOW);
  }
  pinMode(START_BUTTON, INPUT_PULLUP);
  buttonLastRaw = digitalRead(START_BUTTON);
  buttonStable = buttonLastRaw;
  buttonChangedAt = millis();
  pinMode(PASSIVE_BUZZER, OUTPUT);
  pinMode(ACTIVE_BUZZER, OUTPUT);
  pinMode(LED_R, OUTPUT); pinMode(LED_G, OUTPUT); pinMode(LED_B, OUTPUT);
  digitalWrite(ACTIVE_BUZZER, LOW);
  setRgb(false, false, false);
  Serial.begin(9600);
}

void loop() {
  pollStartButton();
  unsigned long now = millis();
  if (activeBeepUntil != 0 && (long)(now - activeBeepUntil) >= 0) {
    digitalWrite(ACTIVE_BUZZER, LOW);
    activeBeepUntil = 0;
  }

  if (phase == COUNTDOWN) {
    unsigned long elapsed = now - phaseStartedAt;
    if (elapsed >= COUNTDOWN_MS) {
      startGame();
    } else {
      byte second = (byte)(elapsed / 1000UL) + 1;
      if (second != lastCountdownNumber) {
        lastCountdownNumber = second;
        tone(PASSIVE_BUZZER, 800, 140);
        setRgb(second % 2 == 0, second % 2 != 0, false);
      }
    }
  } else if (phase == PLAYING) {
    if (now - phaseStartedAt >= GAME_MS) {
      endGame();
    } else {
      pollOneSensor();
      if (flashUntil != 0 && (long)(millis() - flashUntil) >= 0) {
        flashUntil = 0;
        setRgb(false, true, false);
      }
    }
  }
}
