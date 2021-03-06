function xhr(url, callback) {
    var req = new XMLHttpRequest();
    req.onreadystatechange = function() {
    	if (req.readyState == 4) {
    	    if (req.status >= 300) {
    		callback(null, 'Error code ' + req.status);
    	    } else {
    		callback(JSON.parse(req.responseText));
    	    }
    	}
    };
    
    req.open('GET', url, true);
    req.responseType = 'text';
    req.send('');
}

function initAutocomplete(i) {
    i.addEventListener('input', function(ev) {
        xhr('/api/lookup?domain=' + i.dataset.domain +
            '&prefix=' + i.value,
            function(resp) {
                if (resp && resp.indexOf(i.value) >= 0) {
                    i.style.background = '#aaeeaa';
                } else {
                    i.style.background = 'white';
                }
            }
           );
    }, false);
}

function initClearButton(i) {
    if (i.type !== 'text') {
        return;
    }

    var clear = document.createElement('span');
    clear.textContent = 'x';
    clear.className = 'clear-button';
    var parent = i.parentNode;
    var ns = i.nextSibling;
    if (ns) {
        parent.insertBefore(clear, ns);
    } else {
        parent.appendChild(clear);
    }
    clear.addEventListener('click', function(ev) {
        i.value = '';
    }, false);
}

function initMultiInput(holder) {
    var inputs = Array.prototype.slice.call(
                   holder.querySelectorAll('input')
                 );
    var check = function() {
        if (!inputs.some(function(i) {return i.value == ''})) {
            console.log('no empty inputs');
            var fc = holder.firstChild;
            var nc = fc.cloneNode(true);
            var nci = nc.querySelector('input');
            holder.appendChild(nc);
            nci.value = '';
            nci.addEventListener('keypress', check);
            inputs.push(nci);
        }
    };
    for (var i = 0; i < inputs.length; ++i) {
        inputs[i].addEventListener('keypress', check);
    }
        
}

document.addEventListener('DOMContentLoaded', function(ev) {
    var acs = document.querySelectorAll('input.autocomplete');
    for (var i = 0; i < acs.length; ++i) {
        initAutocomplete(acs[i]);
    }

    var inputs = document.querySelectorAll('input');
    for (var i = 0; i < inputs.length; ++i) {
        initClearButton(inputs[i]);
    }

    var multis = document.querySelectorAll('.multi-input');
    for (var i = 0; i < multis.length; ++i) {
        initMultiInput(multis[i]);
    }
}, false);
