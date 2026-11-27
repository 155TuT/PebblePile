import { StyleSheet } from 'react-native';
import PebbleClient from './modules/pebble-client';

export default function App() {
  return <PebbleClient style={styles.client} homeTitle="叠石" />;
}

const styles = StyleSheet.create({
  client: { flex: 1 },
});
