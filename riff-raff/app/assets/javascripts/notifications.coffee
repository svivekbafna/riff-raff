intervalId = null

checkStatus = () ->
  if $('[data-run-state]').length != 0
    buildName = window.riffraff.buildName
    stage = window.riffraff.stage
    buildId = window.riffraff.buildId
    switch $('[data-run-state]').data('run-state')
      when 'Failed' then new Notification('Riffraff', {body: 'Deployment of ' + buildName + ' (' + buildId + ')' + ' in ' + stage + ' has failed!'})
      when 'Completed' then new Notification('Riffraff', {body: 'Deployment of ' + buildName + ' (' + buildId + ')' + ' in ' + stage + ' has finished'})
    disableCheck()

if !window.riffraff.isDone
  Notification.requestPermission()
  intervalId = setInterval checkStatus, 800

disableCheck = ->
  clearInterval(intervalId) if intervalId?