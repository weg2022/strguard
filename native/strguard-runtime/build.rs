use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn main() {
    let output = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo did not provide OUT_DIR"));
    let generated = PathBuf::from(
        env::var_os("CARGO_MANIFEST_DIR").expect("Cargo did not provide CARGO_MANIFEST_DIR"),
    )
    .join("generated");
    if generated.join("native_config.rs").is_file() && generated.join("vault.bin").is_file() {
        let config_directory = generated;
        copy(
            &config_directory.join("native_config.rs"),
            &output.join("native_config.rs"),
        );
        copy(
            &config_directory.join("vault.bin"),
            &output.join("vault.bin"),
        );
        println!(
            "cargo:rerun-if-changed={}",
            config_directory.join("native_config.rs").display()
        );
        println!(
            "cargo:rerun-if-changed={}",
            config_directory.join("vault.bin").display()
        );
    } else {
        write_test_fixture(&output);
    }
}

fn copy(source: &Path, destination: &Path) {
    fs::copy(source, destination).unwrap_or_else(|failure| {
        panic!(
            "failed to copy {} to {}: {failure}",
            source.display(),
            destination.display()
        )
    });
}

fn write_test_fixture(output: &Path) {
    let orders = (0_u8..32)
        .map(|value| format!("0x{value:02x}"))
        .collect::<Vec<_>>();
    let order = format!("[{}]", orders.join(", "));
    let zeroes = "[0x00; 32]";
    let config = format!(
        "pub const BRIDGE_CLASS: &str = \"java/lang/Object\";\n\
         pub const METHOD_NAMES: [&str; 8] = [\"a\", \"b\", \"c\", \"d\", \"e\", \"f\", \"g\", \"h\"];\n\
         pub const BUILD_ID: [u8; 16] = [0x00; 16];\n\
         pub static ENCODED_KEY_SHARES: [[u8; 32]; 4] = [{zeroes}, {zeroes}, {zeroes}, {zeroes}];\n\
         pub static KEY_SHARE_MASKS: [[u8; 32]; 4] = [{zeroes}, {zeroes}, {zeroes}, {zeroes}];\n\
         pub static KEY_SHARE_ORDERS: [[u8; 32]; 4] = [{order}, {order}, {order}, {order}];\n"
    );
    fs::write(output.join("native_config.rs"), config)
        .expect("failed to write the StrGuard Rust test config");
    let mut vault = Vec::from(&b"SGV3"[..]);
    vault.push(3);
    vault.extend_from_slice(&[0_u8; 16]);
    vault.extend_from_slice(&0_u32.to_le_bytes());
    fs::write(output.join("vault.bin"), vault)
        .expect("failed to write the StrGuard Rust test vault");
}
