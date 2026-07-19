use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{ChaCha20Poly1305, KeyInit, Nonce};
use hkdf::Hkdf;
use jni::objects::JClass;
use jni::sys::{jint, jlong, jstring, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM, NativeMethod};
use sha2::Sha256;
use std::ffi::c_void;
use std::ptr;
use zeroize::{Zeroize, Zeroizing};

include!(concat!(env!("STRGUARD_CONFIG_DIR"), "/native_config.rs"));

static VAULT: &[u8] = include_bytes!(concat!(env!("STRGUARD_CONFIG_DIR"), "/vault.bin"));

const MAGIC: &[u8; 4] = b"SGV2";
const VERSION: u8 = 2;
const CAPABILITY_SIZE: usize = 16;
const NONCE_SIZE: usize = 12;
const TAG_SIZE: usize = 16;
const GATEWAY_SIGNATURE: &str = "(JJ)Ljava/lang/String;";
const RECORD_KEY_LABEL: &[u8] = b"strguard/v2/record-key";

#[derive(Debug)]
enum VaultError {
    InvalidFormat,
    RecordNotFound,
    AuthenticationFailed,
    InvalidUtf8,
}

impl VaultError {
    fn message(&self) -> &'static str {
        match self {
            Self::InvalidFormat => "Invalid StrGuard vault",
            Self::RecordNotFound => "Unknown StrGuard capability",
            Self::AuthenticationFailed => "StrGuard vault authentication failed",
            Self::InvalidUtf8 => "Invalid UTF-8 in StrGuard vault",
        }
    }
}

struct Record<'a> {
    nonce: &'a [u8],
    plaintext_length: usize,
    ciphertext: &'a [u8],
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let mut env = match vm.get_env() {
        Ok(env) => env,
        Err(_) => return jni::sys::JNI_ERR,
    };
    let class = match env.find_class(BRIDGE_CLASS) {
        Ok(class) => class,
        Err(_) => return jni::sys::JNI_ERR,
    };
    let function_pointers = gateway_function_pointers();
    let methods: Vec<NativeMethod> = METHOD_NAMES
        .iter()
        .zip(function_pointers)
        .map(|(name, function)| NativeMethod {
            name: (*name).into(),
            sig: GATEWAY_SIGNATURE.into(),
            fn_ptr: function,
        })
        .collect();

    if env.register_native_methods(class, &methods).is_err() {
        return jni::sys::JNI_ERR;
    }
    JNI_VERSION_1_6
}

macro_rules! gateway {
    ($name:ident, $index:expr) => {
        extern "system" fn $name(
            mut env: JNIEnv,
            _class: JClass,
            capability_high: jlong,
            capability_low: jlong,
        ) -> jstring {
            decode_to_java_string(
                &mut env,
                $index,
                capability_high as u64,
                capability_low as u64,
            )
        }
    };
}

gateway!(gateway_0, 0);
gateway!(gateway_1, 1);
gateway!(gateway_2, 2);
gateway!(gateway_3, 3);
gateway!(gateway_4, 4);
gateway!(gateway_5, 5);
gateway!(gateway_6, 6);
gateway!(gateway_7, 7);

fn gateway_function_pointers() -> [*mut c_void; 8] {
    [
        gateway_0 as *mut c_void,
        gateway_1 as *mut c_void,
        gateway_2 as *mut c_void,
        gateway_3 as *mut c_void,
        gateway_4 as *mut c_void,
        gateway_5 as *mut c_void,
        gateway_6 as *mut c_void,
        gateway_7 as *mut c_void,
    ]
}

fn decode_to_java_string(
    env: &mut JNIEnv,
    gateway_index: u8,
    capability_high: u64,
    capability_low: u64,
) -> jstring {
    match decrypt(gateway_index, capability_high, capability_low) {
        Ok(plaintext) => {
            let text = match std::str::from_utf8(&plaintext) {
                Ok(text) => text,
                Err(_) => return throw_and_return_null(env, VaultError::InvalidUtf8),
            };
            match env.new_string(text) {
                Ok(value) => value.into_raw(),
                Err(_) => ptr::null_mut(),
            }
        }
        Err(error) => throw_and_return_null(env, error),
    }
}

fn throw_and_return_null(env: &mut JNIEnv, error: VaultError) -> jstring {
    let _ = env.throw_new("java/lang/IllegalStateException", error.message());
    ptr::null_mut()
}

fn decrypt(
    gateway_index: u8,
    capability_high: u64,
    capability_low: u64,
) -> Result<Zeroizing<Vec<u8>>, VaultError> {
    let mut capability = [0_u8; CAPABILITY_SIZE];
    capability[..8].copy_from_slice(&capability_high.to_be_bytes());
    capability[8..].copy_from_slice(&capability_low.to_be_bytes());
    let record = find_record(&capability, gateway_index)?;
    let mut master_key = Zeroizing::new(reconstruct_master_key());
    let record_key = Zeroizing::new(derive_record_key(
        master_key.as_ref(),
        &capability,
        gateway_index,
    )?);
    master_key.zeroize();
    let cipher = ChaCha20Poly1305::new_from_slice(record_key.as_ref())
        .map_err(|_| VaultError::InvalidFormat)?;
    let associated_data = associated_data(&capability, gateway_index, record.plaintext_length);
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(record.nonce),
            Payload {
                msg: record.ciphertext,
                aad: &associated_data,
            },
        )
        .map_err(|_| VaultError::AuthenticationFailed)?;
    if plaintext.len() != record.plaintext_length {
        return Err(VaultError::InvalidFormat);
    }
    Ok(Zeroizing::new(plaintext))
}

fn reconstruct_master_key() -> [u8; 32] {
    let mut master = [0_u8; 32];
    for share_index in 0..ENCODED_KEY_SHARES.len() {
        for encoded_index in 0..ENCODED_KEY_SHARES[share_index].len() {
            let encoded =
                unsafe { ptr::read_volatile(&ENCODED_KEY_SHARES[share_index][encoded_index]) };
            let mask = unsafe { ptr::read_volatile(&KEY_SHARE_MASKS[share_index][encoded_index]) };
            let target = unsafe {
                ptr::read_volatile(&KEY_SHARE_ORDERS[share_index][encoded_index]) as usize
            };
            master[target] ^= encoded ^ mask;
        }
    }
    std::hint::black_box(master)
}

fn derive_record_key(
    master_key: &[u8],
    capability: &[u8; CAPABILITY_SIZE],
    gateway_index: u8,
) -> Result<[u8; 32], VaultError> {
    let hkdf = Hkdf::<Sha256>::new(Some(&BUILD_ID), master_key);
    let mut info = Vec::with_capacity(RECORD_KEY_LABEL.len() + CAPABILITY_SIZE + 1);
    info.extend_from_slice(RECORD_KEY_LABEL);
    info.extend_from_slice(capability);
    info.push(gateway_index);
    let mut output = [0_u8; 32];
    hkdf.expand(&info, &mut output)
        .map_err(|_| VaultError::InvalidFormat)?;
    info.zeroize();
    Ok(output)
}

fn associated_data(
    capability: &[u8; CAPABILITY_SIZE],
    gateway_index: u8,
    plaintext_length: usize,
) -> Vec<u8> {
    let mut data = Vec::with_capacity(4 + 1 + BUILD_ID.len() + CAPABILITY_SIZE + 1 + 4);
    data.extend_from_slice(MAGIC);
    data.push(VERSION);
    data.extend_from_slice(&BUILD_ID);
    data.extend_from_slice(capability);
    data.push(gateway_index);
    data.extend_from_slice(&(plaintext_length as u32).to_le_bytes());
    data
}

fn find_record(
    requested_capability: &[u8; CAPABILITY_SIZE],
    requested_gateway: u8,
) -> Result<Record<'static>, VaultError> {
    if VAULT.len() < 4 + 1 + BUILD_ID.len() + 4 || &VAULT[..4] != MAGIC || VAULT[4] != VERSION {
        return Err(VaultError::InvalidFormat);
    }
    if VAULT[5..5 + BUILD_ID.len()] != BUILD_ID {
        return Err(VaultError::InvalidFormat);
    }

    let mut cursor = 5 + BUILD_ID.len();
    let record_count = read_u32(VAULT, &mut cursor)? as usize;
    for _ in 0..record_count {
        let body_length = read_u32(VAULT, &mut cursor)? as usize;
        let body_end = cursor
            .checked_add(body_length)
            .ok_or(VaultError::InvalidFormat)?;
        if body_end > VAULT.len()
            || body_length < CAPABILITY_SIZE + 1 + NONCE_SIZE + 4 + 4 + TAG_SIZE + 2
        {
            return Err(VaultError::InvalidFormat);
        }

        let capability = take(VAULT, &mut cursor, CAPABILITY_SIZE)?;
        let gateway = *take(VAULT, &mut cursor, 1)?
            .first()
            .ok_or(VaultError::InvalidFormat)?;
        let nonce = take(VAULT, &mut cursor, NONCE_SIZE)?;
        let plaintext_length = read_u32(VAULT, &mut cursor)? as usize;
        let ciphertext_length = read_u32(VAULT, &mut cursor)? as usize;
        if ciphertext_length < TAG_SIZE {
            return Err(VaultError::InvalidFormat);
        }
        let ciphertext = take(VAULT, &mut cursor, ciphertext_length)?;
        let padding_length = read_u16(VAULT, &mut cursor)? as usize;
        let _padding = take(VAULT, &mut cursor, padding_length)?;
        if cursor != body_end {
            return Err(VaultError::InvalidFormat);
        }

        if capability == requested_capability && gateway == requested_gateway {
            return Ok(Record {
                nonce,
                plaintext_length,
                ciphertext,
            });
        }
    }
    if cursor != VAULT.len() {
        return Err(VaultError::InvalidFormat);
    }
    Err(VaultError::RecordNotFound)
}

fn take<'a>(input: &'a [u8], cursor: &mut usize, size: usize) -> Result<&'a [u8], VaultError> {
    let end = cursor.checked_add(size).ok_or(VaultError::InvalidFormat)?;
    if end > input.len() {
        return Err(VaultError::InvalidFormat);
    }
    let value = &input[*cursor..end];
    *cursor = end;
    Ok(value)
}

fn read_u32(input: &[u8], cursor: &mut usize) -> Result<u32, VaultError> {
    let bytes: [u8; 4] = take(input, cursor, 4)?
        .try_into()
        .map_err(|_| VaultError::InvalidFormat)?;
    Ok(u32::from_le_bytes(bytes))
}

fn read_u16(input: &[u8], cursor: &mut usize) -> Result<u16, VaultError> {
    let bytes: [u8; 2] = take(input, cursor, 2)?
        .try_into()
        .map_err(|_| VaultError::InvalidFormat)?;
    Ok(u16::from_le_bytes(bytes))
}
