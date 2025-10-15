# PSPhone Android Client - Setup Instructions

## Android Client Prompt: Setup and Code Description for PSPhone Mirroring

### Overview
The PSPhone Android client enables seamless screen mirroring from Android devices to PlayStation consoles. This client captures the device screen, encodes the video stream, and transmits it to the PlayStation receiver for display.

### Core Components

#### 1. **MirrorEngine**
The `MirrorEngine` is the heart of the Android client's screen mirroring functionality:
- **Screen Capture**: Uses Android's MediaProjection API to capture screen content
- **Video Encoding**: Encodes captured frames using hardware-accelerated H.264/H.265 codecs
- **Stream Management**: Handles bitrate adaptation and frame rate control
- **Network Transmission**: Manages WebRTC or custom UDP/TCP streaming protocols

**Key Features:**
- Real-time screen capture at configurable resolutions (720p, 1080p)
- Adaptive bitrate based on network conditions
- Low-latency encoding pipeline
- Touch input forwarding capabilities

#### 2. **SessionManager**
The `SessionManager` handles connection lifecycle and session state:
- **Device Discovery**: Discovers available PlayStation receivers on the local network
- **Authentication**: Manages secure pairing between Android device and PlayStation
- **Session State**: Maintains connection status and handles reconnection logic
- **Configuration**: Stores user preferences and streaming settings

**Key Features:**
- Automatic PlayStation device discovery via mDNS/Bonjour
- Secure pairing with PIN-based authentication
- Session persistence and automatic reconnection
- Multi-device support

### Integration Steps

#### Step 1: Prerequisites
- Android Studio Arctic Fox or later
- Android SDK 26 (Android 8.0) or higher
- Gradle 7.0+
- Network access to PlayStation device

#### Step 2: Setup Development Environment
```bash
# Clone the repository
git clone https://github.com/POWDER-RANGER/PSPhone.git
cd PSPhone/android

# Open in Android Studio
# File > Open > Select android directory
```

#### Step 3: Build Configuration
1. Sync Gradle dependencies
2. Configure signing certificates (for release builds)
3. Set up emulator or connect physical Android device
4. Ensure minimum SDK version is set to 26

#### Step 4: Running the Application
```bash
# Debug build
./gradlew assembleDebug
./gradlew installDebug

# Release build
./gradlew assembleRelease
```

#### Step 5: Testing Connection
1. Ensure Android device and PlayStation are on the same network
2. Launch the PSPhone app on Android
3. Launch the PSPhone receiver on PlayStation
4. Follow pairing instructions to establish connection

### PlayStation Receiver Summary

The PlayStation receiver component runs on PS4/PS5 consoles and handles:

**Core Functionality:**
- **Stream Reception**: Receives encoded video stream from Android client
- **Video Decoding**: Hardware-accelerated decoding of H.264/H.265 streams
- **Display Rendering**: Renders decoded frames to PlayStation display output
- **Input Forwarding**: Sends PlayStation controller input back to Android device (optional)

**Network Requirements:**
- Local network connectivity (Wi-Fi or Ethernet recommended)
- Port forwarding for WebRTC/UDP streams
- Low-latency network (<50ms preferred)

**Performance:**
- Supports up to 1080p@60fps streaming
- Adaptive quality based on network conditions
- <100ms end-to-end latency in optimal conditions

### Usage Instructions

#### For End Users:
1. **Install**: Download and install PSPhone APK on your Android device
2. **Launch**: Open the PSPhone app
3. **Grant Permissions**: Allow screen capture and network access when prompted
4. **Discover**: Tap "Find PlayStation" to discover available receivers
5. **Pair**: Enter the PIN displayed on your PlayStation screen
6. **Stream**: Once connected, your Android screen will appear on PlayStation
7. **Controls**: Use the app controls to adjust quality, pause, or disconnect

#### For Developers:
1. **Explore MirrorEngine**: Check `app/src/main/java/.../MirrorEngine.java` for screen capture implementation
2. **Review SessionManager**: See `app/src/main/java/.../SessionManager.java` for connection handling
3. **Modify Settings**: Adjust streaming parameters in configuration files
4. **Debug**: Use Android Studio debugger and logcat for troubleshooting
5. **Test**: Run unit tests with `./gradlew test`

### Key Classes to Explore

#### MirrorEngine
- `MirrorEngine.initialize()` - Setup screen capture and encoder
- `MirrorEngine.startCapture()` - Begin screen mirroring
- `MirrorEngine.stopCapture()` - End mirroring session
- `MirrorEngine.setQuality()` - Adjust stream quality

#### SessionManager
- `SessionManager.discoverDevices()` - Find PlayStation receivers
- `SessionManager.connectToDevice()` - Establish connection
- `SessionManager.disconnect()` - Close connection
- `SessionManager.getSessionState()` - Check connection status

### Contributing

Contributors should focus on:
- **MirrorEngine improvements**: Enhance encoding efficiency, reduce latency
- **SessionManager enhancements**: Improve connection stability, add features
- **UI/UX**: Better user interface and experience
- **Performance**: Optimize battery usage and thermal management
- **Compatibility**: Test on various Android devices and PlayStation models

**Before submitting PRs:**
1. Review code in `MirrorEngine` and `SessionManager` modules
2. Ensure all tests pass
3. Follow project coding standards
4. Update documentation for new features
5. Test on multiple devices

### Troubleshooting

**Connection Issues:**
- Verify both devices are on the same network
- Check firewall settings
- Restart both applications

**Performance Issues:**
- Lower streaming quality in settings
- Check network bandwidth
- Close background applications

**Quality Issues:**
- Adjust bitrate settings
- Check network latency
- Verify PlayStation decoder compatibility

### Additional Resources

- Main README: `../README.md`
- PlayStation Receiver Documentation: `../playstation/README.md`
- API Documentation: Generate with `./gradlew javadoc`
- Issue Tracker: GitHub Issues

---

**Note**: For detailed implementation specifics, please refer to the inline code documentation in `MirrorEngine` and `SessionManager` classes.
