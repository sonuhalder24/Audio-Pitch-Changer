
// Utility: convert semitone shift to pitch factor
function semitonesToFactor(semitones) {
    return Math.pow(2, semitones / 12);
}

const semRange = document.getElementById('semitones');
const semNum = document.getElementById('semitones-number');
semRange.addEventListener('input', () => semNum.value = semRange.value);
semNum.addEventListener('input', () => {
    let v = Number(semNum.value);
    if (isNaN(v)) v = 0;
    if (v < -12) v = -12;
    if (v > 12) v = 12;
    semNum.value = v;
    semRange.value = v;
});

const shiftSection = document.getElementById('shift-section');
const noteSection = document.getElementById('note-section');
document.querySelectorAll('input[name="mode"]').forEach(radio => {
    radio.addEventListener('change', () => {
        if (radio.value === 'shift' && radio.checked) {
            shiftSection.classList.remove('section-hidden');
            noteSection.classList.add('section-hidden');
        } else if (radio.value === 'note' && radio.checked) {
            shiftSection.classList.add('section-hidden');
            noteSection.classList.remove('section-hidden');
        }
    });
});

const form = document.getElementById('form');
const submitBtn = document.getElementById('submitBtn');
const progressBar = document.getElementById('progressBar');
const progressWrap = document.querySelector('.progress-wrapper');
const output = document.getElementById('output');
const player = document.getElementById('player');
const downloadLink = document.getElementById('downloadLink');
const resultFilename = document.getElementById('resultFilename');
const clearBtn = document.getElementById('clearBtn');

// Configure your backend API endpoint here
const ENDPOINT = '/api/audio/pitch'; // Change this to your actual backend URL if different

function setUploading(isUploading, progressText = '') {
    submitBtn.disabled = isUploading;
    submitBtn.innerHTML = isUploading ? '⏳ Processing...' : '✨ Process Audio';
    progressWrap.style.display = isUploading ? 'block' : 'none';

    if (isUploading) {
        progressBar.style.width = '10%';
        document.getElementById('progressText').textContent = progressText || 'Uploading file...';
    } else {
        progressBar.style.width = '0%';
        document.getElementById('progressText').textContent = '';
    }
}

// File input display update
const fileInput = document.getElementById('file');
const fileDisplay = document.querySelector('.file-input-display .file-input-content');

fileInput.addEventListener('change', (e) => {
    if (e.target.files && e.target.files.length) {
        const file = e.target.files[0];
        fileDisplay.innerHTML = `
            <div class="file-icon">🎵</div>
            <div>
                <strong>${file.name}</strong>
                <div class="helper-text">${(file.size / (1024*1024)).toFixed(1)} MB • Ready to process</div>
            </div>
        `;
    }
});

form.addEventListener('submit', async (e) => {
    e.preventDefault();

    if (!fileInput.files || !fileInput.files.length) {
        alert('Please select an MP3 file first.');
        return;
    }

    const file = fileInput.files[0];

    // Validate file type
    if (!file.type.includes('audio') && !file.name.toLowerCase().endsWith('.mp3')) {
        alert('Please select a valid MP3 file.');
        return;
    }

    // Validate file size (50MB limit)
    const maxSize = 50 * 1024 * 1024; // 50MB in bytes
    if (file.size > maxSize) {
        alert('File size must be less than 50MB.');
        return;
    }

    // Get the selected mode
    const mode = document.querySelector('input[name="mode"]:checked').value;

    // Create FormData for multipart/form-data submission
    const formData = new FormData();
    formData.append('file', file, file.name);
    //formData.append('keepTempo', 'true'); // Keep tempo unchanged

    if (mode === 'shift') {
        const semitones = Number(semRange.value || '0');
        //const shiftFactor = semitonesToFactor(semitones);
        //formData.append('shift', shiftFactor);

        formData.append('shift', semitones.toString()); // just integer
        console.log('Shift mode - Semitones:', semitones);
    } else {
        const note = document.getElementById('note').value.trim();
        if (!note) {
            alert("Please enter a target note (e.g., C4, A#3, Bb5).");
            return;
        }
        formData.append('targetNote', note);
        console.log('Note mode - Target note:', note);
    }

    // Log FormData contents for debugging
    console.log('FormData contents:');
    for (let pair of formData.entries()) {
        console.log(pair[0] + ':', pair[1]);
    }

    setUploading(true, 'Uploading and processing audio...');

    try {
        // Simulate progress updates
        const progressInterval = setInterval(() => {
            const currentWidth = parseInt(progressBar.style.width) || 10;
            if (currentWidth < 90) {
                progressBar.style.width = (currentWidth + 10) + '%';
                if (currentWidth < 30) {
                    document.getElementById('progressText').textContent = 'Uploading file...';
                } else if (currentWidth < 70) {
                    document.getElementById('progressText').textContent = 'Processing audio...';
                } else {
                    document.getElementById('progressText').textContent = 'Almost done...';
                }
            }
        }, 500);

        // Using fetch for better error handling
        const response = await fetch(ENDPOINT, {
            method: 'POST',
            body: formData,
            // Don't set Content-Type header - let browser set it for FormData
        });

        clearInterval(progressInterval);
        progressBar.style.width = '100%';
        document.getElementById('progressText').textContent = 'Processing complete!';

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`HTTP ${response.status}: ${errorText}`);
        }

        // Check if response is actually a file
        const contentType = response.headers.get('content-type');
        if (!contentType || (!contentType.includes('audio') && !contentType.includes('octet-stream'))) {
            const errorText = await response.text();
            throw new Error('Invalid response format: ' + errorText);
        }

        const blob = await response.blob();

        // Get filename from Content-Disposition header
        const contentDisposition = response.headers.get('Content-Disposition') || '';
        let filename = 'processed_audio';

        const filenameMatch = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/.exec(contentDisposition);
        if (filenameMatch && filenameMatch[1]) {
            filename = filenameMatch[1].replace(/['"]/g, '');
        } else {
            // Generate filename based on original file and processing
            const originalName = file.name.replace(/\.[^/.]+$/, ''); // Remove extension
            if (mode === 'shift') {
                const semitones = Number(semRange.value || '0');
                filename = `${originalName}_shift_${semitones}st.mp3`;
            } else {
                const note = document.getElementById('note').value.trim();
                filename = `${originalName}_to_${note}.mp3`;
            }
        }

        // Create object URL and set up audio player
        const audioUrl = URL.createObjectURL(blob);
        player.src = audioUrl;
        player.load();

        // Set up download link
        downloadLink.href = audioUrl;
        downloadLink.download = filename;

        // Update UI
        resultFilename.textContent = '🎵 ' + filename;
        output.style.display = 'block';
        output.scrollIntoView({ behavior: 'smooth' });

        console.log('Processing successful:', filename);

    } catch (error) {
        console.error('Upload error:', error);
        alert(`Processing failed: ${error.message}`);
    } finally {
        setUploading(false);
    }
});

clearBtn.addEventListener('click', () => {
    if (player.src) URL.revokeObjectURL(player.src);
    player.pause();
    player.src = '';
    downloadLink.href = '#';
    output.style.display = 'none';
    fileInput.value = '';
    semRange.value = 0;
    semNum.value = 0;
    document.getElementById('note').value = '';

    // Reset file display
    fileDisplay.innerHTML = `
        <div class="file-icon">📁</div>
        <div>
            <strong>Click to browse</strong> or drag and drop
            <div class="helper-text">MP3 files only • Max 50MB</div>
        </div>
    `;
});
