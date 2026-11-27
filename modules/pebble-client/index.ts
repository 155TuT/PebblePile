import { requireNativeView } from 'expo';
import type { ViewProps } from 'react-native';

// A stable component type keeps the mounted Compose tree across edits to App.tsx.
export default requireNativeView<ViewProps & { homeTitle: string }>('PebbleClient');
