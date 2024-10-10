import { execSync } from 'child_process';
import os from 'os';

// Function to get console width
export function getConsoleWidth(): number {
  const defaultWidth = 120; // Default width for fallback
  let width = defaultWidth;

  try {
    if (process.stdout && process.stdout.columns) {
      width = process.stdout.columns;
    } else if (process.env.COLUMNS) {
      width = parseInt(process.env.COLUMNS, 10);
    } else {
      // Use OS-specific commands to get terminal width
      const platform = os.platform();
      if (platform === 'win32') {
        const output = execSync('mode con', { encoding: 'utf8' }).trim();
        const match = output.match(/Columns:\s+(\d+)/);
        if (match) {
          width = parseInt(match[1], 10);
        }
      } else if (platform === 'linux' || platform === 'darwin') {
        const output = execSync('tput cols', { encoding: 'utf8' }).trim();
        width = parseInt(output, 10) || defaultWidth;
      }
    }
  } catch (error) {
    console.error('Failed to get console width, using default width:', error);
  }
  return width;
}
