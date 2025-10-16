import log from 'electron-log/main';
import remoteModPacksManager from '@main/modPacks/RemoteModPacksManager';

export function preStartup(): void {
  // Setup logging
  log.initialize();
  log.eventLogger.startLogging();
  log.errorHandler.startCatching();
  // TODO: ACTUAL LOG LEVEL LOGIC FROM CONFIG
  log.transports.console.level = 'silly';
  log.transports.file.level = 'silly';
  log.debug('Logging initialized.');

  // Log start of pre-startup tasks
  log.debug('Running pre-startup tasks...');

  // Handle graceful shutdown if necessary
  log.info('Configuring graceful shutdown handlers...');
  const signals: NodeJS.Signals[] = ['SIGINT', 'SIGTERM'];
  signals.forEach((signal) => {
    process.on(signal, () => {
      log.info(`Received ${signal}, initiating graceful shutdown...`);
      preShutdown();
      process.exit(0);
    });
  });

  // Queue update of available mod packs
  log.debug('Queuing mod pack data refresh...');
  remoteModPacksManager
    .refreshModPackData()
    .then(() => {
      log.debug('Mod pack data refresh completed.');
    })
    .catch((error) => {
      log.error('Error refreshing mod pack data:', error);
    });

  // Log completion of pre-startup tasks
  log.debug('Pre-startup tasks completed.');
}

export function postStartup(): void {
  // Log start of post-startup tasks
  log.debug('Running post-startup tasks...');

  // Additional post-startup tasks can be added here

  // Log completion of post-startup tasks
  log.debug('Post-startup tasks completed.');
}

export function preShutdown(): void {
  // Log start of pre-shutdown tasks
  log.debug('Running pre-shutdown tasks...');

  // Additional pre-shutdown tasks can be added here

  // Log completion of pre-shutdown tasks
  log.debug('Pre-shutdown tasks completed.');
}
