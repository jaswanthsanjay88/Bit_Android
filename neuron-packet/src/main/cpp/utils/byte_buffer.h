#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <cstring>

namespace neuron {

class ByteBuffer {
public:
    ByteBuffer() = default;
    explicit ByteBuffer(size_t capacity);
    explicit ByteBuffer(const std::vector<uint8_t>& data);
    ByteBuffer(const uint8_t* data, size_t size);

    void writeByte(uint8_t value);
    void writeShort(uint16_t value);
    void writeInt(uint32_t value);
    void writeLong(uint64_t value);
    void writeBytes(const uint8_t* data, size_t size);
    void writeBytes(const std::vector<uint8_t>& data);
    void writeString(const std::string& str, size_t fixedSize = 0);

    uint8_t readByte();
    uint16_t readShort();
    uint32_t readInt();
    uint64_t readLong();
    std::vector<uint8_t> readBytes(size_t count);
    std::string readString(size_t size);

    void seek(size_t pos);
    size_t position() const { return pos_; }
    size_t size() const { return data_.size(); }
    const uint8_t* data() const { return data_.data(); }
    uint8_t* data() { return data_.data(); }
    std::vector<uint8_t>& buffer() { return data_; }
    const std::vector<uint8_t>& buffer() const { return data_; }
    void clear();
    void resize(size_t newSize);

private:
    std::vector<uint8_t> data_;
    size_t pos_ = 0;
};

} // namespace neuron