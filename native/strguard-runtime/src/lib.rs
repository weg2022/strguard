use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{ChaCha20Poly1305, KeyInit, Nonce};
use hkdf::Hkdf;
use jni::objects::{GlobalRef, JClass, JString};
use jni::sys::{jint, jlong, jsize, jstring, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM, NativeMethod};
use sha2::Sha256;
use std::collections::{HashMap, HashSet};
use std::ffi::c_void;
use std::ptr;
use std::sync::Mutex;
use zeroize::{Zeroize, Zeroizing};

include!(concat!(env!("OUT_DIR"), "/native_config.rs"));

static VAULT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/vault.bin"));
static RUNTIME: Mutex<Option<RuntimeState<'static>>> = Mutex::new(None);

const MAGIC: &[u8; 4] = b"SGV3";
const VERSION: u8 = 3;
const CAPABILITY_SIZE: usize = 16;
const NONCE_SIZE: usize = 12;
const TAG_SIZE: usize = 16;
const GATEWAY_SIGNATURE: &str = "(JJ)Ljava/lang/String;";
const RECORD_KEY_LABEL: &[u8] = b"strguard/v3/record-key";

#[derive(Debug)]
enum VaultError {
    InvalidFormat,
    RecordNotFound,
    AuthenticationFailed,
    RuntimeUnavailable,
    JvmFailure,
}

impl VaultError {
    fn message(&self) -> &'static str {
        match self {
            Self::InvalidFormat => "Invalid StrGuard vault",
            Self::RecordNotFound => "Unknown StrGuard capability",
            Self::AuthenticationFailed => "StrGuard vault authentication failed",
            Self::RuntimeUnavailable => "StrGuard Native runtime is unavailable",
            Self::JvmFailure => "StrGuard could not create the protected Java string",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct RecordKey {
    capability: [u8; CAPABILITY_SIZE],
    gateway: u8,
}

struct Record<'a> {
    nonce: [u8; NONCE_SIZE],
    code_unit_count: usize,
    ciphertext: &'a [u8],
}

struct CachedRecord<'a> {
    encrypted: Record<'a>,
    decoded: Option<GlobalRef>,
}

struct RuntimeState<'a> {
    records: HashMap<RecordKey, CachedRecord<'a>>,
}

impl<'a> RuntimeState<'a> {
    fn parse(input: &'a [u8]) -> Result<Self, VaultError> {
        if input.len() < 4 + 1 + BUILD_ID.len() + 4
            || &input[..MAGIC.len()] != MAGIC
            || input[MAGIC.len()] != VERSION
            || input[5..5 + BUILD_ID.len()] != BUILD_ID
        {
            return Err(VaultError::InvalidFormat);
        }

        let mut cursor = 5 + BUILD_ID.len();
        let record_count = read_u32(input, &mut cursor)? as usize;
        let minimum_record_size = 4 + CAPABILITY_SIZE + 1 + NONCE_SIZE + 4 + 4 + TAG_SIZE + 2;
        if record_count > input.len().saturating_sub(cursor) / minimum_record_size {
            return Err(VaultError::InvalidFormat);
        }

        let mut records = HashMap::with_capacity(record_count);
        let mut capabilities = HashSet::with_capacity(record_count);
        for _ in 0..record_count {
            let body_length = read_u32(input, &mut cursor)? as usize;
            let body_end = cursor
                .checked_add(body_length)
                .ok_or(VaultError::InvalidFormat)?;
            if body_end > input.len()
                || body_length < CAPABILITY_SIZE + 1 + NONCE_SIZE + 4 + 4 + TAG_SIZE + 2
            {
                return Err(VaultError::InvalidFormat);
            }

            let capability: [u8; CAPABILITY_SIZE] = take(input, &mut cursor, CAPABILITY_SIZE)?
                .try_into()
                .map_err(|_| VaultError::InvalidFormat)?;
            if !capabilities.insert(capability) {
                return Err(VaultError::InvalidFormat);
            }
            let gateway = *take(input, &mut cursor, 1)?
                .first()
                .ok_or(VaultError::InvalidFormat)?;
            if gateway as usize >= METHOD_NAMES.len() {
                return Err(VaultError::InvalidFormat);
            }
            let nonce: [u8; NONCE_SIZE] = take(input, &mut cursor, NONCE_SIZE)?
                .try_into()
                .map_err(|_| VaultError::InvalidFormat)?;
            let code_unit_count = read_u32(input, &mut cursor)? as usize;
            if code_unit_count > jsize::MAX as usize {
                return Err(VaultError::InvalidFormat);
            }
            let ciphertext_length = read_u32(input, &mut cursor)? as usize;
            let expected_ciphertext_length = code_unit_count
                .checked_mul(2)
                .and_then(|size| size.checked_add(TAG_SIZE))
                .ok_or(VaultError::InvalidFormat)?;
            if ciphertext_length != expected_ciphertext_length {
                return Err(VaultError::InvalidFormat);
            }
            let ciphertext = take(input, &mut cursor, ciphertext_length)?;
            let padding_length = read_u16(input, &mut cursor)? as usize;
            let _padding = take(input, &mut cursor, padding_length)?;
            if cursor != body_end {
                return Err(VaultError::InvalidFormat);
            }

            let key = RecordKey {
                capability,
                gateway,
            };
            if records
                .insert(
                    key,
                    CachedRecord {
                        encrypted: Record {
                            nonce,
                            code_unit_count,
                            ciphertext,
                        },
                        decoded: None,
                    },
                )
                .is_some()
            {
                return Err(VaultError::InvalidFormat);
            }
        }
        if cursor != input.len() {
            return Err(VaultError::InvalidFormat);
        }
        Ok(Self { records })
    }
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let state = match RuntimeState::parse(VAULT) {
        Ok(state) => state,
        Err(_) => return jni::sys::JNI_ERR,
    };
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
    let mut runtime = match RUNTIME.lock() {
        Ok(runtime) => runtime,
        Err(_) => return jni::sys::JNI_ERR,
    };
    if runtime.is_some() {
        return jni::sys::JNI_ERR;
    }
    *runtime = Some(state);
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn JNI_OnUnload(_vm: JavaVM, _reserved: *mut c_void) {
    if let Ok(mut runtime) = RUNTIME.lock() {
        runtime.take();
    }
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
    gateway: u8,
    capability_high: u64,
    capability_low: u64,
) -> jstring {
    match decode_and_cache(env, gateway, capability_high, capability_low) {
        Ok(value) => value,
        Err(error) => throw_and_return_null(env, error),
    }
}

fn decode_and_cache(
    env: &mut JNIEnv,
    gateway: u8,
    capability_high: u64,
    capability_low: u64,
) -> Result<jstring, VaultError> {
    let mut capability = [0_u8; CAPABILITY_SIZE];
    capability[..8].copy_from_slice(&capability_high.to_be_bytes());
    capability[8..].copy_from_slice(&capability_low.to_be_bytes());
    let key = RecordKey {
        capability,
        gateway,
    };

    let mut runtime = RUNTIME.lock().map_err(|_| VaultError::RuntimeUnavailable)?;
    let state = runtime.as_mut().ok_or(VaultError::RuntimeUnavailable)?;
    let record = state
        .records
        .get_mut(&key)
        .ok_or(VaultError::RecordNotFound)?;
    if record.decoded.is_none() {
        let code_units = decrypt(&record.encrypted, &key)?;
        record.decoded = Some(create_interned_string(env, &code_units)?);
    }
    let decoded = record.decoded.as_ref().ok_or(VaultError::JvmFailure)?;
    let local = env
        .new_local_ref(decoded.as_obj())
        .map_err(|_| VaultError::JvmFailure)?;
    Ok(local.into_raw() as jstring)
}

fn create_interned_string(env: &mut JNIEnv, code_units: &[u16]) -> Result<GlobalRef, VaultError> {
    let raw_env = env.get_raw();
    let new_string = unsafe { (**raw_env).NewString }.ok_or(VaultError::JvmFailure)?;
    let raw_string = unsafe { new_string(raw_env, code_units.as_ptr(), code_units.len() as jsize) };
    if raw_string.is_null() || env.exception_check().map_err(|_| VaultError::JvmFailure)? {
        return Err(VaultError::JvmFailure);
    }
    let string = unsafe { JString::from_raw(raw_string) };
    let interned = env
        .call_method(&string, "intern", "()Ljava/lang/String;", &[])
        .and_then(|value| value.l())
        .map_err(|_| VaultError::JvmFailure)?;
    env.new_global_ref(interned)
        .map_err(|_| VaultError::JvmFailure)
}

fn throw_and_return_null(env: &mut JNIEnv, error: VaultError) -> jstring {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/IllegalStateException", error.message());
    }
    ptr::null_mut()
}

fn decrypt(record: &Record<'_>, key: &RecordKey) -> Result<Zeroizing<Vec<u16>>, VaultError> {
    let mut master_key = Zeroizing::new(reconstruct_master_key());
    let record_key = Zeroizing::new(derive_record_key(
        master_key.as_ref(),
        &key.capability,
        key.gateway,
    )?);
    master_key.zeroize();
    let cipher = ChaCha20Poly1305::new_from_slice(record_key.as_ref())
        .map_err(|_| VaultError::InvalidFormat)?;
    let associated_data = Zeroizing::new(associated_data(
        &key.capability,
        key.gateway,
        record.code_unit_count,
    ));
    let plaintext = Zeroizing::new(
        cipher
            .decrypt(
                Nonce::from_slice(&record.nonce),
                Payload {
                    msg: record.ciphertext,
                    aad: associated_data.as_ref(),
                },
            )
            .map_err(|_| VaultError::AuthenticationFailed)?,
    );
    if plaintext.len() != record.code_unit_count * 2 {
        return Err(VaultError::InvalidFormat);
    }

    let mut code_units = Zeroizing::new(Vec::with_capacity(record.code_unit_count));
    for bytes in plaintext.chunks_exact(2) {
        code_units.push(u16::from_le_bytes([bytes[0], bytes[1]]));
    }
    Ok(code_units)
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
    gateway: u8,
) -> Result<[u8; 32], VaultError> {
    let hkdf = Hkdf::<Sha256>::new(Some(&BUILD_ID), master_key);
    let mut info = Zeroizing::new(Vec::with_capacity(
        RECORD_KEY_LABEL.len() + CAPABILITY_SIZE + 1,
    ));
    info.extend_from_slice(RECORD_KEY_LABEL);
    info.extend_from_slice(capability);
    info.push(gateway);
    let mut output = [0_u8; 32];
    hkdf.expand(&info, &mut output)
        .map_err(|_| VaultError::InvalidFormat)?;
    Ok(output)
}

fn associated_data(
    capability: &[u8; CAPABILITY_SIZE],
    gateway: u8,
    code_unit_count: usize,
) -> Vec<u8> {
    let mut data = Vec::with_capacity(4 + 1 + BUILD_ID.len() + CAPABILITY_SIZE + 1 + 4);
    data.extend_from_slice(MAGIC);
    data.push(VERSION);
    data.extend_from_slice(&BUILD_ID);
    data.extend_from_slice(capability);
    data.push(gateway);
    data.extend_from_slice(&(code_unit_count as u32).to_le_bytes());
    data
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::hint::black_box;
    use std::time::Instant;

    #[test]
    fn accepts_an_empty_v3_vault() {
        let vault = vault_with_records(&[]);
        let parsed = RuntimeState::parse(&vault).expect("valid empty vault");
        assert!(parsed.records.is_empty());
    }

    #[test]
    fn rejects_legacy_and_trailing_data() {
        let mut legacy = vault_with_records(&[]);
        legacy[..4].copy_from_slice(b"SGV2");
        legacy[4] = 2;
        assert!(matches!(
            RuntimeState::parse(&legacy),
            Err(VaultError::InvalidFormat)
        ));

        let mut trailing = vault_with_records(&[]);
        trailing.push(0);
        assert!(matches!(
            RuntimeState::parse(&trailing),
            Err(VaultError::InvalidFormat)
        ));
    }

    #[test]
    fn rejects_duplicate_capabilities_and_invalid_gateway() {
        let capability = [7_u8; CAPABILITY_SIZE];
        let encoded_record = record(capability, 0);
        let duplicate = vault_with_records(&[encoded_record.clone(), encoded_record]);
        assert!(matches!(
            RuntimeState::parse(&duplicate),
            Err(VaultError::InvalidFormat)
        ));

        let invalid_gateway = vault_with_records(&[record(capability, METHOD_NAMES.len() as u8)]);
        assert!(matches!(
            RuntimeState::parse(&invalid_gateway),
            Err(VaultError::InvalidFormat)
        ));
    }

    #[test]
    fn rejects_truncated_and_oversized_record_fields() {
        let valid = vault_with_records(&[record([9_u8; CAPABILITY_SIZE], 0)]);
        for end in 0..valid.len() {
            assert_invalid(&valid[..end]);
        }

        let record_count_offset = 5 + BUILD_ID.len();
        let mut oversized_count = vault_with_records(&[]);
        oversized_count[record_count_offset..record_count_offset + 4]
            .copy_from_slice(&u32::MAX.to_le_bytes());
        assert_invalid(&oversized_count);

        let body_length_offset = record_count_offset + 4;
        let body_start = body_length_offset + 4;
        let code_unit_count_offset = body_start + CAPABILITY_SIZE + 1 + NONCE_SIZE;
        let ciphertext_length_offset = code_unit_count_offset + 4;

        let mut oversized_body = valid.clone();
        oversized_body[body_length_offset..body_length_offset + 4]
            .copy_from_slice(&u32::MAX.to_le_bytes());
        assert_invalid(&oversized_body);

        let mut oversized_code_units = valid.clone();
        oversized_code_units[code_unit_count_offset..code_unit_count_offset + 4]
            .copy_from_slice(&((jsize::MAX as u32) + 1).to_le_bytes());
        assert_invalid(&oversized_code_units);

        let mut mismatched_ciphertext = valid.clone();
        mismatched_ciphertext[ciphertext_length_offset..ciphertext_length_offset + 4]
            .copy_from_slice(&0_u32.to_le_bytes());
        assert_invalid(&mismatched_ciphertext);

        let ciphertext_length = 2 + TAG_SIZE;
        let padding_length_offset = ciphertext_length_offset + 4 + ciphertext_length;
        let mut oversized_padding = valid;
        oversized_padding[padding_length_offset..padding_length_offset + 2]
            .copy_from_slice(&u16::MAX.to_le_bytes());
        assert_invalid(&oversized_padding);
    }

    #[test]
    #[ignore = "release-mode performance gate"]
    fn performance_shape_is_linear_for_1k_5k_10k_records() {
        let counts = [1_000_usize, 5_000, 10_000];
        let mut parse_medians = Vec::new();
        let mut lookup_medians = Vec::new();

        for count in counts {
            let records: Vec<Vec<u8>> = (0..count)
                .map(|index| record(capability_for(index), (index % METHOD_NAMES.len()) as u8))
                .collect();
            let vault = vault_with_records(&records);
            let parse_repetitions = 100_000 / count;
            let parse_samples: Vec<u128> = (0..7)
                .map(|_| {
                    let started = Instant::now();
                    for _ in 0..parse_repetitions {
                        let state = RuntimeState::parse(black_box(&vault))
                            .expect("benchmark vault must parse");
                        black_box(state.records.len());
                    }
                    started.elapsed().as_nanos() / parse_repetitions as u128
                })
                .collect();
            let state = RuntimeState::parse(&vault).expect("benchmark vault must parse");
            let lookup_samples: Vec<u128> = (0..7)
                .map(|sample| {
                    let started = Instant::now();
                    for index in 0..100_000_usize {
                        let record_index = (index.wrapping_mul(31).wrapping_add(sample)) % count;
                        let key = RecordKey {
                            capability: capability_for(record_index),
                            gateway: (record_index % METHOD_NAMES.len()) as u8,
                        };
                        black_box(
                            state
                                .records
                                .get(black_box(&key))
                                .expect("record must exist"),
                        );
                    }
                    started.elapsed().as_nanos()
                })
                .collect();
            parse_medians.push(median(parse_samples));
            lookup_medians.push(median(lookup_samples));
        }

        println!("StrGuard parse medians ns (1k/5k/10k): {parse_medians:?}");
        println!("StrGuard 100k lookup medians ns (1k/5k/10k): {lookup_medians:?}");
        assert!(parse_medians[1] <= parse_medians[0].saturating_mul(10));
        assert!(parse_medians[2] <= parse_medians[1].saturating_mul(10));
        assert!(lookup_medians[2] <= lookup_medians[0].saturating_mul(2));
        assert!(parse_medians[2] <= 10_000_000);
        assert!(lookup_medians[2] <= 10_000_000);
    }

    fn vault_with_records(records: &[Vec<u8>]) -> Vec<u8> {
        let mut vault = Vec::from(&MAGIC[..]);
        vault.push(VERSION);
        vault.extend_from_slice(&BUILD_ID);
        vault.extend_from_slice(&(records.len() as u32).to_le_bytes());
        records
            .iter()
            .for_each(|record| vault.extend_from_slice(record));
        vault
    }

    fn assert_invalid(vault: &[u8]) {
        assert!(matches!(
            RuntimeState::parse(vault),
            Err(VaultError::InvalidFormat)
        ));
    }

    fn record(capability: [u8; CAPABILITY_SIZE], gateway: u8) -> Vec<u8> {
        let code_unit_count = 1_u32;
        let ciphertext_length = code_unit_count * 2 + TAG_SIZE as u32;
        let mut body = Vec::new();
        body.extend_from_slice(&capability);
        body.push(gateway);
        body.extend_from_slice(&[0_u8; NONCE_SIZE]);
        body.extend_from_slice(&code_unit_count.to_le_bytes());
        body.extend_from_slice(&ciphertext_length.to_le_bytes());
        body.extend_from_slice(&vec![0_u8; ciphertext_length as usize]);
        body.extend_from_slice(&0_u16.to_le_bytes());

        let mut output = Vec::new();
        output.extend_from_slice(&(body.len() as u32).to_le_bytes());
        output.extend_from_slice(&body);
        output
    }

    fn capability_for(index: usize) -> [u8; CAPABILITY_SIZE] {
        let mut capability = [0_u8; CAPABILITY_SIZE];
        capability[..8].copy_from_slice(&(index as u64).to_le_bytes());
        capability[8..].copy_from_slice(&(!(index as u64)).to_le_bytes());
        capability
    }

    fn median(mut samples: Vec<u128>) -> u128 {
        samples.sort_unstable();
        samples[samples.len() / 2]
    }
}
