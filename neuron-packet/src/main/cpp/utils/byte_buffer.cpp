#include "byte_buffer.h"
#include <stdexcept>

namespace neuron {

ByteBuffer::ByteBuffer(size_t capacity) {
    data_.reserve(capacity);
}

ByteBuffer::ByteBuffer(const std::vector<uint8_t>& data) : data_(data) {}

ByteBuffer::ByteBuffer(const uint8_t* data, size_t size) : data_(data, data + size) {}

void ByteBuffer::writeByte(uint8_t value) {
    data_.push_back(value);
}

void ByteBuffer::writeShort(uint16_t value) {
    data_.push_back(static_cast<uint8_t>(value & 0xFF));
    data_.push_back(static_cast<uint8_t>((value >> 8) & 0xFF));
}

void ByteBuffer::writeInt(uint32_t value) {
    for (int i = 0; i < 4; i++) {
        data_.push_back(static_cast<uint8_t>((value >> (i * 8)) & 0xFF));
    }
}

void ByteBuffer::writeLong(uint64_t value) {
    for (int i = 0; i < 8; i++) {
        data_.push_back(static_cast<uint8_t>((value >> (i * 8)) & 0xFF));
    }
}

void ByteBuffer::writeBytes(const uint8_t* data, size_t size) {
    data_.insert(data_.end(), data, data + size);
}

void ByteBuffer::writeBytes(const std::vector<uint8_t>& data) {
    data_.insert(data_.end(), data.begin(), data.end());
}

void ByteBuffer::writeString(const std::string& str, size_t fixedSize) {
    if (fixedSize == 0) {
        writeBytes(reinterpret_cast<const uint8_t*>(str.data()), str.size());
    } else {
        size_t writeSize = std::min(str.size(), fixedSize);
        writeBytes(reinterpret_cast<const uint8_t*>(str.data()), writeSize);
        for (size_t i = writeSize; i < fixedSize; i++) {
            writeByte(0);
        }
    }
}

uint8_t ByteBuffer::readByte() {
    if (pos_ >= data_.size()) throw std::runtime_error("Buffer underflow");
    return data_[pos_++];
}

uint16_t ByteBuffer::readShort() {
    uint16_t value = 0;
    for (int i = 0; i < 2; i++) {
        value |= static_cast<uint16_t>(readByte()) << (i * 8);
    }
    return value;
}

uint32_t ByteBuffer::readInt() {
    uint32_t value = 0;
    for (int i = 0; i < 4; i++) {
        value |= static_cast<uint32_t>(readByte()) << (i * 8);
    }
    return value;
}

uint64_t ByteBuffer::readLong() {
    uint64_t value = 0;
    for (int i = 0; i < 8; i++) {
        value |= static_cast<uint64_t>(readByte()) << (i * 8);
    }
    return value;
}

std::vector<uint8_t> ByteBuffer::readBytes(size_t count) {
    if (pos_ + count > data_.size()) throw std::runtime_error("Buffer underflow");
    std::vector<uint8_t> result(data_.begin() + pos_, data_.begin() + pos_ + count);
    pos_ += count;
    return result;
}

std::string ByteBuffer::readString(size_t size) {
    auto bytes = readBytes(size);
    auto end = std::find(bytes.begin(), bytes.end(), 0);
    return std::string(bytes.begin(), end);
}

void ByteBuffer::seek(size_t pos) {
    pos_ = pos;
}

void ByteBuffer::clear() {
    data_.clear();
    pos_ = 0;
}

void ByteBuffer::resize(size_t newSize) {
    data_.resize(newSize);
}

} // namespace neuron