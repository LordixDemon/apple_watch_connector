//! JNI uses bounded integer handles, never Java-supplied native pointers.
use crate::{Engine, State};
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JObjectArray, JString},
    sys::{jlong, jobjectArray, jstring},
};
use std::{
    net::{Ipv6Addr, SocketAddr},
    sync::{
        Mutex,
        atomic::{AtomicI64, Ordering},
    },
    time::Instant,
};
use zeroize::Zeroizing;

static SESSION: Mutex<Option<(i64, Engine)>> = Mutex::new(None);
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn boundary<T: Default>(
    env: &mut JNIEnv<'_>,
    operation: impl FnOnce(&mut JNIEnv<'_>) -> Result<T, String>,
) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            T::default()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "QUIC native operation failed",
            );
            T::default()
        }
    }
}

fn bytes(env: &JNIEnv<'_>, input: &JByteArray<'_>, maximum: usize) -> Result<Vec<u8>, String> {
    let length = env
        .get_array_length(input)
        .map_err(|_| "Invalid QUIC byte array")? as usize;
    if length == 0 || length > maximum {
        return Err("QUIC byte array outside bounds".into());
    }
    env.convert_byte_array(input)
        .map_err(|_| "Cannot read QUIC byte array".into())
}
fn address(env: &JNIEnv<'_>, input: &JByteArray<'_>, port: i32) -> Result<SocketAddr, String> {
    let octets: [u8; 16] = bytes(env, input, 16)?
        .try_into()
        .map_err(|_| "QUIC requires IPv6")?;
    if !(1..=65535).contains(&port) {
        return Err("Invalid QUIC UDP port".into());
    }
    Ok(SocketAddr::new(Ipv6Addr::from(octets).into(), port as u16))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_applewatchandroid_bridge_NativeQuicSession_nativeCreate(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    private: JByteArray<'_>,
    pins: JObjectArray<'_>,
    local: JByteArray<'_>,
    local_port: i32,
    remote: JByteArray<'_>,
    remote_port: i32,
) -> jlong {
    boundary(&mut env, |env| {
        let private = Zeroizing::new(bytes(env, &private, 4096)?);
        let count = env
            .get_array_length(&pins)
            .map_err(|_| "Invalid QUIC pins")?;
        if !(1..=8).contains(&count) {
            return Err("Invalid QUIC pin count".into());
        }
        let mut keys = Vec::with_capacity(count as usize);
        for i in 0..count {
            let key = JByteArray::from(
                env.get_object_array_element(&pins, i)
                    .map_err(|_| "Invalid QUIC pin")?,
            );
            keys.push(bytes(env, &key, 512)?);
        }
        let engine = Engine::new(
            &private,
            keys,
            address(env, &local, local_port)?,
            address(env, &remote, remote_port)?,
            Instant::now(),
        )?;
        let mut session = SESSION.lock().map_err(|_| "QUIC session unavailable")?;
        if session.is_some() {
            return Err("QUIC session already exists".into());
        }
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        *session = Some((handle, engine));
        Ok(handle)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_applewatchandroid_bridge_NativeQuicSession_nativeReceive(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    handle: jlong,
    data: JByteArray<'_>,
) {
    boundary(&mut env, |env| {
        let data = bytes(env, &data, crate::MAX_DATAGRAM)?;
        let mut guard = SESSION.lock().map_err(|_| "QUIC session unavailable")?;
        let (_, engine) = guard
            .as_mut()
            .filter(|(id, _)| *id == handle)
            .ok_or("Stale QUIC handle")?;
        engine.receive(&data, Instant::now())
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_applewatchandroid_bridge_NativeQuicSession_nativePoll(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    handle: jlong,
) -> jobjectArray {
    boundary(&mut env, |env| {
        let mut guard = SESSION.lock().map_err(|_| "QUIC session unavailable")?;
        let (_, engine) = guard
            .as_mut()
            .filter(|(id, _)| *id == handle)
            .ok_or("Stale QUIC handle")?;
        let datagrams = engine.poll(Instant::now())?;
        let result = env
            .new_object_array(datagrams.len() as i32, "[B", jni::objects::JObject::null())
            .map_err(|_| "Cannot allocate bounded QUIC output")?;
        for (i, data) in datagrams.iter().enumerate() {
            let array = env
                .byte_array_from_slice(data)
                .map_err(|_| "Cannot copy bounded QUIC output")?;
            env.set_object_array_element(&result, i as i32, array)
                .map_err(|_| "Cannot return QUIC output")?;
        }
        Ok(result.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_applewatchandroid_bridge_NativeQuicSession_nativeState(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    handle: jlong,
) -> jstring {
    boundary(&mut env, |env| {
        let guard = SESSION.lock().map_err(|_| "QUIC session unavailable")?;
        let (_, engine) = guard
            .as_ref()
            .filter(|(id, _)| *id == handle)
            .ok_or("Stale QUIC handle")?;
        let value = match engine.state() {
            State::Connecting => "connecting".into(),
            State::Connected => "connected".into(),
            State::Failed => format!(
                "failed: {}",
                engine
                    .failure()
                    .unwrap_or("connection closed")
                    .chars()
                    .filter(|c| !c.is_control())
                    .take(160)
                    .collect::<String>()
            ),
        };
        let value: JString<'_> = env
            .new_string(value)
            .map_err(|_| "Cannot return QUIC state")?;
        Ok(value.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_applewatchandroid_bridge_NativeQuicSession_nativeClose(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    handle: jlong,
) {
    boundary(&mut env, |_| {
        let mut guard = SESSION.lock().map_err(|_| "QUIC session unavailable")?;
        if guard.as_ref().is_some_and(|(id, _)| *id == handle) {
            *guard = None;
        }
        Ok(())
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_applewatchandroid_bridge_NativeQuicSession_nativeSend(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    handle: jlong,
    data: JByteArray<'_>,
) {
    boundary(&mut env, |env| {
        let data = bytes(env, &data, 64 * 1024)?;
        let mut guard = SESSION.lock().map_err(|_| "QUIC session unavailable")?;
        let (_, engine) = guard
            .as_mut()
            .filter(|(id, _)| *id == handle)
            .ok_or("Stale QUIC handle")?;
        engine.send(&data)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_applewatchandroid_bridge_NativeQuicSession_nativeRead(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    handle: jlong,
) -> jobjectArray {
    boundary(&mut env, |env| {
        let mut guard = SESSION.lock().map_err(|_| "QUIC session unavailable")?;
        let (_, engine) = guard
            .as_mut()
            .filter(|(id, _)| *id == handle)
            .ok_or("Stale QUIC handle")?;
        let chunks = engine.read()?;
        let result = env
            .new_object_array(chunks.len() as i32, "[B", jni::objects::JObject::null())
            .map_err(|_| "Cannot allocate bounded QUIC stream output")?;
        for (i, chunk) in chunks.iter().enumerate() {
            let mut data = Vec::with_capacity(9 + chunk.data.len());
            data.extend_from_slice(&chunk.stream.to_be_bytes());
            data.push(u8::from(chunk.finished));
            data.extend_from_slice(&chunk.data);
            let array = env
                .byte_array_from_slice(&data)
                .map_err(|_| "Cannot copy QUIC stream output")?;
            env.set_object_array_element(&result, i as i32, array)
                .map_err(|_| "Cannot return QUIC stream output")?;
        }
        Ok(result.into_raw())
    })
}
