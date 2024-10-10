import { preShutdown } from './events';

export function startHeadless(): void {
  console.log('Running in headless mode...');
  // Add your headless logic here

  // Handle graceful shutdown if necessary
  process.on('SIGINT', () => {
    preShutdown();
    process.exit(0);
  });
  process.on('SIGTERM', () => {
    preShutdown();
    process.exit(0);
  });
}
