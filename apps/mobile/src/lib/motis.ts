// Client for the MOTIS routing server (services/motis).
// On a USB-connected Android phone, run `adb reverse tcp:8080 tcp:8080` so
// localhost on the phone reaches the server on this machine.
export const MOTIS_URL = process.env.EXPO_PUBLIC_MOTIS_URL ?? 'http://localhost:8080';

export async function isMotisUp(): Promise<boolean> {
  try {
    const res = await fetch(`${MOTIS_URL}/api/v1/health`);
    return res.ok;
  } catch {
    return false;
  }
}
