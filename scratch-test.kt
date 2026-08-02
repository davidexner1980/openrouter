        try {
            stream.write(encodeGoal(goal).toString().toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            error.printStackTrace()
            atomicFile.failWrite(stream)
            throw error
        }
