const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
// The project root also contains native builds and local validation checkouts.
config.resolver.blockList.push(
  /[/\\]\.(?:build|local|gradle|kotlin)(?:[/\\]|$)/,
  /[/\\]build[/\\]native(?:[/\\]|$)/,
);

module.exports = config;
